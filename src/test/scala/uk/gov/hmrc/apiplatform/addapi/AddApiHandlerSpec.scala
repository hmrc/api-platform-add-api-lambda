package uk.gov.hmrc.apiplatform.addapi

import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.swagger.models.{Info, Swagger}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.Op.ADD
import software.amazon.awssdk.services.apigateway.model._
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{SendMessageRequest, SendMessageResponse}
import uk.gov.hmrc.api_platform_manage_api.{DeploymentService, SwaggerService}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.JsonMapper

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

class AddApiHandlerSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
    val usagePlans: Map[String, String] = Map("BRONZE" -> "1", "SILVER" -> "2")
    val apiId: String = UUID.randomUUID().toString
    val apiName = "foo--1.0"
    val version = "1.0"
    val context = "a/context"
    val requestBody = s"""{"host": "localhost", "info": {"title": "$apiName"}}"""
    val message = new SQSMessage()
    message.setBody(requestBody)
    val sqsEvent = new SQSEvent()
    sqsEvent.setRecords(List(message))

    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val mockSqsClient: SqsClient = mock[SqsClient]
    val mockSwaggerService: SwaggerService = mock[SwaggerService]
    val mockDeploymentService: DeploymentService = mock[DeploymentService]
    val mockContext: Context = mock[Context]
    when(mockContext.getLogger).thenReturn(mock[LambdaLogger])
    when(mockAPIGatewayClient.getRestApis(any[GetRestApisRequest])).thenReturn(buildNonMatchingRestApisResponse(3))
    when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenReturn(GetUsagePlanResponse.builder().build())

    val swagger: Swagger = new Swagger().
      host("localhost").
      info(new Info().title(apiName).version(version)).
      basePath(s"/$context")
    when(mockSwaggerService.createSwagger(any[String])).thenReturn(swagger)
  }

  trait StandardSetup extends Setup {
    val environment: Map[String, String] = Map("endpoint_type" -> "REGIONAL", "usage_plans" -> toJson(usagePlans), "update_usage_plan_queue" -> "arn:queue")
    val addApiHandler = new UpsertApiHandler(mockAPIGatewayClient, mockSqsClient, mockDeploymentService, mockSwaggerService, environment)
  }

  trait SetupWithoutEndpointType extends Setup {
    val environment: Map[String, String] = Map()
    val addApiHandler = new UpsertApiHandler(mockAPIGatewayClient, mockSqsClient, mockDeploymentService, mockSwaggerService, environment)
  }

  "Add API Handler" should {
    "send API specification to AWS endpoint" in new StandardSetup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

      addApiHandler.handleInput(sqsEvent, mockContext)
    }

    "correctly convert request event into ImportRestApiRequest with correct configuration" in new StandardSetup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
      val importRestApiRequestCaptor: ArgumentCaptor[ImportRestApiRequest] = ArgumentCaptor.forClass(classOf[ImportRestApiRequest])
      when(mockAPIGatewayClient.importRestApi(importRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)

      addApiHandler.handleInput(sqsEvent, mockContext)

      val capturedRequest: ImportRestApiRequest = importRestApiRequestCaptor.getValue
      capturedRequest.parameters should contain(Entry("endpointConfigurationTypes", "REGIONAL"))
      capturedRequest.failOnWarnings shouldBe true
      capturedRequest.body shouldEqual fromUtf8String(toJson(swagger))
    }

    "default to PRIVATE if no endpoint type specified in the environment" in new SetupWithoutEndpointType {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
      val importRestApiRequestCaptor: ArgumentCaptor[ImportRestApiRequest] = ArgumentCaptor.forClass(classOf[ImportRestApiRequest])
      when(mockAPIGatewayClient.importRestApi(importRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)

      addApiHandler.handleInput(sqsEvent, mockContext)

      val capturedRequest: ImportRestApiRequest = importRestApiRequestCaptor.getValue
      capturedRequest.parameters should contain(Entry("endpointConfigurationTypes", "PRIVATE"))
    }

    "deploy API" in new StandardSetup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

      addApiHandler.handleInput(sqsEvent, mockContext)

      verify(mockDeploymentService, times(1)).deployApi(apiId, context, version)
    }

    "add the API to usage plans" in new StandardSetup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)
      val sendMessageRequestCaptor: ArgumentCaptor[SendMessageRequest] = ArgumentCaptor.forClass(classOf[SendMessageRequest])
      when(mockSqsClient.sendMessage(sendMessageRequestCaptor.capture())).thenReturn(SendMessageResponse.builder().build())

      addApiHandler.handleInput(sqsEvent, mockContext)

      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.getAllValues.asScala
      capturedRequests should have size 2
      val firstRequest: UsagePlanUpdateMsg = fromJson[UsagePlanUpdateMsg](capturedRequests.head.messageBody)
      firstRequest.usagePlanId shouldBe usagePlans("BRONZE")
      firstRequest.patchOperations should contain only PatchOp(ADD.toString, "/apiStages", s"$apiId:current")
      val secondRequest: UsagePlanUpdateMsg = fromJson[UsagePlanUpdateMsg](capturedRequests(1).messageBody)
      secondRequest.usagePlanId shouldBe usagePlans("SILVER")
      secondRequest.patchOperations should contain only PatchOp(ADD.toString, "/apiStages", s"$apiId:current")
    }

    "not add the API to usage plans that already contain the API" in new StandardSetup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)
      val sendMessageRequestCaptor: ArgumentCaptor[SendMessageRequest] = ArgumentCaptor.forClass(classOf[SendMessageRequest])
      when(mockSqsClient.sendMessage(sendMessageRequestCaptor.capture())).thenReturn(SendMessageResponse.builder().build())
      val getUsagePlanResponseForBronze: GetUsagePlanResponse = GetUsagePlanResponse.builder().build()
      val getUsagePlanResponseForSilver: GetUsagePlanResponse = GetUsagePlanResponse.builder().apiStages(ApiStage.builder().apiId(apiId).stage("current").build()).build()
      when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenReturn(getUsagePlanResponseForBronze, getUsagePlanResponseForSilver)

      addApiHandler.handleInput(sqsEvent, mockContext)

      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.getAllValues.asScala
      capturedRequests should have size 1
      val capturedRequest: UsagePlanUpdateMsg = fromJson[UsagePlanUpdateMsg](capturedRequests.head.messageBody)
      capturedRequest.usagePlanId shouldBe usagePlans("BRONZE")
      capturedRequest.patchOperations should contain only PatchOp(ADD.toString, "/apiStages", s"$apiId:current")
    }

    "propagate UnauthorizedException thrown by AWS SDK when importing API" in new StandardSetup {
      val errorMessage = "You're an idiot"
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

      val exception: UnauthorizedException = intercept[UnauthorizedException](addApiHandler.handleInput(sqsEvent, mockContext))
      exception.getMessage shouldEqual errorMessage
    }

    "throw exception if the event has no messages" in new StandardSetup {
      sqsEvent.setRecords(List())

      val exception: IllegalArgumentException = intercept[IllegalArgumentException](addApiHandler.handleInput(sqsEvent, mockContext))
      exception.getMessage shouldEqual "Invalid number of records: 0"
    }

    "throw exception if the event has multiple messages" in new StandardSetup {
      sqsEvent.setRecords(List(message, message))

      val exception: IllegalArgumentException = intercept[IllegalArgumentException](addApiHandler.handleInput(sqsEvent, mockContext))
      exception.getMessage shouldEqual "Invalid number of records: 2"
    }
  }

  def buildNonMatchingRestApisResponse(count: Int): GetRestApisResponse = {
    val items: Seq[RestApi] = (1 to count).map(c => RestApi.builder().id(s"$c").name(s"Item $c").build())

    GetRestApisResponse.builder()
      .items(seqAsJavaList(items))
      .build()
  }
}
