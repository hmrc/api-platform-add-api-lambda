package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.{HTTP_OK, HTTP_UNAUTHORIZED}
import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import io.swagger.models.Swagger
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.api_platform_manage_api.{DeploymentService, SwaggerService}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.JsonMapper

class AddApiHandlerSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
    val requestBody = """{"host": "api-example-microservice.protected.mdtp"}"""
    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val mockSwaggerService: SwaggerService = mock[SwaggerService]
    val mockDeploymentService: DeploymentService = mock[DeploymentService]
  }

  trait StandardSetup extends Setup {
    val environment: Map[String, String] = Map("endpoint_type" -> "REGIONAL")
    val addApiHandler = new AddApiHandler(mockAPIGatewayClient, mockDeploymentService, mockSwaggerService, environment)
  }

  trait SetupWithoutEndpointType extends Setup {
    val environment: Map[String, String] = Map()
    val addApiHandler = new AddApiHandler(mockAPIGatewayClient, mockDeploymentService, mockSwaggerService, environment)
  }

  "Add API Handler" should {
    "send API specification to AWS endpoint and return the created id" in new StandardSetup {
      val id: String = UUID.randomUUID().toString
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(id).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

      val response: APIGatewayProxyResponseEvent = addApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("POST")
        .withBody(requestBody)
      )

      response.getStatusCode shouldEqual HTTP_OK
      response.getBody shouldEqual s"""{"restApiId":"$id"}"""
    }

    "correctly convert request event into ImportRestApiRequest with correct configuration" in new StandardSetup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(UUID.randomUUID().toString).build()
      val importRestApiRequestCaptor: ArgumentCaptor[ImportRestApiRequest] = ArgumentCaptor.forClass(classOf[ImportRestApiRequest])
      when(mockAPIGatewayClient.importRestApi(importRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)
      val swagger: Swagger = new Swagger().host("localhost")
      when(mockSwaggerService.createSwagger(any[APIGatewayProxyRequestEvent])).thenReturn(swagger)

      addApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("POST")
        .withBody(requestBody)
      )

      val capturedRequest: ImportRestApiRequest = importRestApiRequestCaptor.getValue
      capturedRequest.parameters should contain(Entry("endpointConfigurationTypes", "REGIONAL"))
      capturedRequest.failOnWarnings shouldBe true
      capturedRequest.body shouldEqual fromUtf8String(toJson(swagger))
    }

    "default to PRIVATE if no endpoint type specified in the environment" in new SetupWithoutEndpointType {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(UUID.randomUUID().toString).build()
      val importRestApiRequestCaptor: ArgumentCaptor[ImportRestApiRequest] = ArgumentCaptor.forClass(classOf[ImportRestApiRequest])
      when(mockAPIGatewayClient.importRestApi(importRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)

      addApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("POST")
        .withBody(requestBody)
      )

      val capturedRequest: ImportRestApiRequest = importRestApiRequestCaptor.getValue
      capturedRequest.parameters should contain(Entry("endpointConfigurationTypes", "PRIVATE"))
    }

    "deploy API" in new StandardSetup {
      val apiId: String = UUID.randomUUID().toString
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

      addApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("POST")
        .withBody(requestBody)
      )

      verify(mockDeploymentService, times(1)).deployApi(apiId)
    }

    "correctly handle UnauthorizedException thrown by AWS SDK when importing API" in new StandardSetup {
      val errorMessage = "You're an idiot"
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

      val response: APIGatewayProxyResponseEvent = addApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("POST")
        .withBody(requestBody)
      )

      response.getStatusCode shouldEqual HTTP_UNAUTHORIZED
      response.getBody shouldEqual errorMessage
    }
  }
}
