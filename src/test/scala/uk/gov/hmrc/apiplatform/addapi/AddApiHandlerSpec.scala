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
import software.amazon.awssdk.services.apigateway.model._
import software.amazon.awssdk.services.waf.model.DisassociateWebAclRequest
import software.amazon.awssdk.services.waf.regional.WafRegionalClient
import uk.gov.hmrc.api_platform_manage_api.{AccessLogConfiguration, DeploymentService, NoCloudWatchLogging, SwaggerService}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.JsonMapper

import scala.collection.JavaConversions._
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AddApiHandlerSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
    val usagePlans: Map[String, String] = Map("BRONZE" -> "1", "SILVER" -> "2")
    val apiId: String = UUID.randomUUID().toString
    val apiNameWithoutVersion = "foo"
    val apiName = s"$apiNameWithoutVersion--1.0"
    val version = "1.0"
    val context = "a/context"
    val requestBody = s"""{"host": "localhost", "info": {"title": "$apiName"}}"""
    val message = new SQSMessage()
    message.setBody(requestBody)
    val sqsEvent = new SQSEvent()
    sqsEvent.setRecords(List(message))
    val loggingDestinationArn: String = "aws:arn:1234567890"

    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val mockUsagePlanService: UsagePlanService = mock[UsagePlanService]
    val mockWafRegionalClient: WafRegionalClient = mock[WafRegionalClient]
    val mockSwaggerService: SwaggerService = mock[SwaggerService]
    val mockDeploymentService: DeploymentService = mock[DeploymentService]
    val mockContext: Context = mock[Context]
    val mockLambdaLogger: LambdaLogger = mock[LambdaLogger]
    when(mockContext.getLogger).thenReturn(mockLambdaLogger)
    when(mockAPIGatewayClient.getRestApis(any[GetRestApisRequest])).thenReturn(buildNonMatchingRestApisResponse(3))
    when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenReturn(GetUsagePlanResponse.builder().build())

    val swagger: Swagger = new Swagger().
      host("localhost").
      info(new Info().title(apiName).version(version)).
      basePath(s"/$context")
    when(mockSwaggerService.createSwagger(any[String])).thenReturn(swagger)
  }

  trait StandardSetup extends Setup {
    val environment: Map[String, String] =
      Map(
        "AWS_REGION" -> "eu-west-2",
        "endpoint_type" -> "REGIONAL",
        "access_log_arn" -> loggingDestinationArn)
    val addApiHandler =
      new UpsertApiHandler(mockAPIGatewayClient, mockUsagePlanService, mockWafRegionalClient, mockDeploymentService, mockSwaggerService, environment, Clock.fixed(Instant.parse("2023-10-02T10:15:30.00Z"), ZoneId.of("UTC")))
  }

  trait SetupWithoutEndpointType extends Setup {
    val environment: Map[String, String] =
      Map(
        "AWS_REGION" -> "eu-west-2",
        "access_log_arn" -> loggingDestinationArn)
    val addApiHandler =
      new UpsertApiHandler(mockAPIGatewayClient, mockUsagePlanService, mockWafRegionalClient, mockDeploymentService, mockSwaggerService, environment)
  }

  "Add API Handler" should {
    "send API specification to AWS endpoint" in new StandardSetup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

      addApiHandler.handleInput(sqsEvent, mockContext)

      verify(mockAPIGatewayClient).importRestApi(any[ImportRestApiRequest])
    }

    "update the swagger description to indicate the date the API was updated" in new StandardSetup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

      addApiHandler.handleInput(sqsEvent, mockContext)

      swagger.getInfo().getDescription() shouldBe "Updated by API Platform add-api-lambda at 2023-10-02" 
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

      verify(mockDeploymentService, times(1))
        .deployApi(apiId, context, version, NoCloudWatchLogging, AccessLogConfiguration(addApiHandler.AccessLogFormat, loggingDestinationArn))
    }

    "disassociate the stage with the web ACL" in new StandardSetup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

      addApiHandler.handleInput(sqsEvent, mockContext)

      verify(mockWafRegionalClient, times(1))
        .disassociateWebACL(DisassociateWebAclRequest
          .builder()
          .resourceArn(s"arn:aws:apigateway:${environment("AWS_REGION")}::/restapis/$apiId/stages/current")
          .build()
        )
    }

    "add the API to usage plans" in new StandardSetup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

      addApiHandler.handleInput(sqsEvent, mockContext)

      verify(mockUsagePlanService).addApiToUsagePlans(apiId, apiNameWithoutVersion)(mockLambdaLogger)
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

  "AccessLogFormat" should {
    "be a single line string" in new StandardSetup {
      addApiHandler.AccessLogFormat should not include ("\n")
      addApiHandler.AccessLogFormat should not include ("\r")
    }
  }

  def buildNonMatchingRestApisResponse(count: Int): GetRestApisResponse = {
    val items: Seq[RestApi] = (1 to count).map(c => RestApi.builder().id(s"$c").name(s"Item $c").build())

    GetRestApisResponse.builder()
      .items(seqAsJavaList(items))
      .build()
  }
}
