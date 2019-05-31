package uk.gov.hmrc.apiplatform.addapi

import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.swagger.models.{Info, Swagger}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.EndpointType.{PRIVATE, REGIONAL}
import software.amazon.awssdk.services.apigateway.model.Op.REPLACE
import software.amazon.awssdk.services.apigateway.model.PutMode.OVERWRITE
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.api_platform_manage_api.{DeploymentService, SwaggerService}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.JsonMapper

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

class UpdateApiHandlerSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
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
    val mockSwaggerService: SwaggerService = mock[SwaggerService]
    val mockDeploymentService: DeploymentService = mock[DeploymentService]
    val mockContext: Context = mock[Context]
    when(mockContext.getLogger).thenReturn(mock[LambdaLogger])
    when(mockAPIGatewayClient.getRestApi(any[GetRestApiRequest]))
      .thenReturn(GetRestApiResponse.builder().endpointConfiguration(EndpointConfiguration.builder().types(REGIONAL).build()).build())
    when(mockAPIGatewayClient.getRestApis(any[GetRestApisRequest])).thenReturn(buildMatchingRestApisResponse(apiId, apiName))

    val swagger: Swagger = new Swagger().
      host("localhost").
      info(new Info().title(apiName).version(version)).
      basePath(s"/$context")
    when(mockSwaggerService.createSwagger(any[String])).thenReturn(swagger)
  }

  trait StandardSetup extends Setup {
    val environment: Map[String, String] = Map("endpoint_type" -> "REGIONAL")
    val updateApiHandler = new UpsertApiHandler(mockAPIGatewayClient, mockDeploymentService, mockSwaggerService, environment)
  }

  trait SetupWithoutEndpointType extends Setup {
    val environment: Map[String, String] = Map()
    val updateApiHandler = new UpsertApiHandler(mockAPIGatewayClient, mockDeploymentService, mockSwaggerService, environment)
  }

  "Update API Handler" should {
    "send API specification to AWS endpoint and return the updated API id" in new StandardSetup {
      val id: String = UUID.randomUUID().toString
      val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(id).build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      updateApiHandler.handleInput(sqsEvent, mockContext)
    }

    "correctly convert request event into PutRestApiRequest with correct configuration" in new StandardSetup {
      val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(apiId).build()
      val putRestApiRequestCaptor: ArgumentCaptor[PutRestApiRequest] = ArgumentCaptor.forClass(classOf[PutRestApiRequest])
      when(mockAPIGatewayClient.putRestApi(putRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)

      updateApiHandler.handleInput(sqsEvent, mockContext)

      val capturedRequest: PutRestApiRequest = putRestApiRequestCaptor.getValue
      capturedRequest.failOnWarnings shouldBe true
      capturedRequest.body shouldEqual fromUtf8String(toJson(swagger))
      capturedRequest.mode() shouldEqual OVERWRITE
      capturedRequest.restApiId() shouldEqual apiId
    }

    "update the endpoint type if the new value doesn't match the current one in AWS" in new StandardSetup {
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(PutRestApiResponse.builder().id(apiId).build())
      val updateRestApiRequestCaptor: ArgumentCaptor[UpdateRestApiRequest] = ArgumentCaptor.forClass(classOf[UpdateRestApiRequest])
      when(mockAPIGatewayClient.updateRestApi(updateRestApiRequestCaptor.capture())).thenReturn(UpdateRestApiResponse.builder().build())
      when(mockAPIGatewayClient.getRestApi(any[GetRestApiRequest]))
        .thenReturn(GetRestApiResponse.builder().endpointConfiguration(EndpointConfiguration.builder().types(PRIVATE).build()).build())

      updateApiHandler.handleInput(sqsEvent, mockContext)

      val capturedUpdateRequest: UpdateRestApiRequest = updateRestApiRequestCaptor.getValue
      val patchOperation: PatchOperation = capturedUpdateRequest.patchOperations().asScala.head
      patchOperation.op() shouldEqual REPLACE
      patchOperation.path() shouldEqual "/endpointConfiguration/types/PRIVATE"
      patchOperation.value() shouldEqual "REGIONAL"
    }

    "not update the endpoint type if the new value matches the current one in AWS" in new StandardSetup {
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(PutRestApiResponse.builder().id(apiId).build())

      updateApiHandler.handleInput(sqsEvent, mockContext)

      verify(mockAPIGatewayClient, Mockito.never()).updateRestApi(any[UpdateRestApiRequest])
    }

    "default to PRIVATE if no endpoint type specified in the environment" in new SetupWithoutEndpointType {
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(PutRestApiResponse.builder().id(apiId).build())
      val updateRestApiRequestCaptor: ArgumentCaptor[UpdateRestApiRequest] = ArgumentCaptor.forClass(classOf[UpdateRestApiRequest])
      when(mockAPIGatewayClient.updateRestApi(updateRestApiRequestCaptor.capture())).thenReturn(UpdateRestApiResponse.builder().build())

      updateApiHandler.handleInput(sqsEvent, mockContext)

      val capturedUpdateRequest: UpdateRestApiRequest = updateRestApiRequestCaptor.getValue
      val patchOperation: PatchOperation = capturedUpdateRequest.patchOperations().asScala.head
      patchOperation.op() shouldEqual REPLACE
      patchOperation.path() shouldEqual "/endpointConfiguration/types/REGIONAL"
      patchOperation.value() shouldEqual "PRIVATE"
    }

    "deploy API" in new StandardSetup {
      val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      updateApiHandler.handleInput(sqsEvent, mockContext)

      verify(mockDeploymentService, times(1)).deployApi(apiId, context, version)
    }

    "propagate UnauthorizedException thrown by AWS SDK when updating API" in new StandardSetup {
      val errorMessage = "You're an idiot"
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

      val exception: UnauthorizedException = intercept[UnauthorizedException](updateApiHandler.handleInput(sqsEvent, mockContext))
      exception.getMessage shouldEqual errorMessage
    }

    "throw exception if the event has no messages" in new StandardSetup {
      sqsEvent.setRecords(List())

      val exception: IllegalArgumentException = intercept[IllegalArgumentException](updateApiHandler.handleInput(sqsEvent, mockContext))
      exception.getMessage shouldEqual "Invalid number of records: 0"
    }

    "throw exception if the event has multiple messages" in new StandardSetup {
      sqsEvent.setRecords(List(message, message))

      val exception: IllegalArgumentException = intercept[IllegalArgumentException](updateApiHandler.handleInput(sqsEvent, mockContext))
      exception.getMessage shouldEqual "Invalid number of records: 2"
    }
  }

  def buildMatchingRestApisResponse(matchingId: String, matchingName: String): GetRestApisResponse = {
    GetRestApisResponse.builder()
      .items(RestApi.builder().id(matchingId).name(matchingName).build())
      .build()
  }
}
