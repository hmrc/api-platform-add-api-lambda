package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.{HTTP_INTERNAL_ERROR, HTTP_OK}
import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.swagger.models.Swagger
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.PutMode.OVERWRITE
import software.amazon.awssdk.services.apigateway.model._

class AddApiHandlerSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
    val inputBody: String = InputBody().toString
    val mockContext: Context = mock[Context]
    when(mockContext.getLogger).thenReturn(mock[LambdaLogger])
    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val mockSwaggerService: SwaggerService = mock[SwaggerService]
    val mockDeploymentService: DeploymentService = mock[DeploymentService]
    val addApiHandler = new AddApiHandler(mockAPIGatewayClient, mockDeploymentService, mockSwaggerService)
  }

  "The Add API handler" when {
    "invoked with PUT" should {
      "send API specification to AWS endpoint and return the updated API id" in new Setup {
        val id: String = UUID.randomUUID().toString
        val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(id).build()
        when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

        val result: Either[Nothing, String] = addApiHandler.handle(InputBody(httpMethod = "PUT", apiId = id).toString, mockContext)

        result.isRight shouldBe true
        val Right(responseEvent) = result
        val response: APIGatewayProxyResponseEvent = fromJson[APIGatewayProxyResponseEvent](responseEvent)
        response.getStatusCode shouldEqual HTTP_OK
        response.getBody shouldEqual s"""{"restApiId":"$id"}"""
      }

      "correctly convert OpenAPI JSON into PutRestApiRequest with correct configuration" in new Setup {
        val apiId: String = UUID.randomUUID().toString
        val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(UUID.randomUUID().toString).build()
        val putRestApiRequestCaptor: ArgumentCaptor[PutRestApiRequest] = ArgumentCaptor.forClass(classOf[PutRestApiRequest])
        when(mockAPIGatewayClient.putRestApi(putRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)
        val swagger: Swagger = new Swagger().host("localhost")
        when(mockSwaggerService.swagger(any[APIGatewayProxyRequestEvent])).thenReturn(swagger)

        val result: Either[Nothing, String] = addApiHandler.handle(InputBody(httpMethod = "PUT", apiId = apiId).toString, mockContext)

        val capturedRequest: PutRestApiRequest = putRestApiRequestCaptor.getValue
        capturedRequest.parameters should contain(Entry("endpointConfigurationTypes", "REGIONAL"))
        capturedRequest.failOnWarnings shouldBe true
        capturedRequest.body shouldEqual fromUtf8String(toJson(swagger))
        capturedRequest.mode() shouldEqual OVERWRITE
        capturedRequest.restApiId() shouldEqual apiId
      }

      "deploy API" in new Setup {
        val apiId: String = UUID.randomUUID().toString
        val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(apiId).build()
        when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

        addApiHandler.handle(InputBody(httpMethod = "PUT", apiId = apiId).toString, mockContext)

        verify(mockDeploymentService, times(1)).deployApi(apiId)
      }

      "correctly handle UnauthorizedException thrown by AWS SDK when updating API" in new Setup {
        val errorMessage = "You're an idiot"
        when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

        val result: Either[Nothing, String] = addApiHandler.handle(InputBody(httpMethod = "PUT", apiId = UUID.randomUUID().toString).toString, mockContext)

        result.isRight shouldBe true
        val Right(responseEvent) = result
        val response: APIGatewayProxyResponseEvent = fromJson[APIGatewayProxyResponseEvent](responseEvent)
        response.getStatusCode shouldEqual HTTP_INTERNAL_ERROR
        response.getBody shouldEqual errorMessage
      }
    }

    "invoked with POST" should {
      "send API specification to AWS endpoint and return the created id" in new Setup {
        val id: String = UUID.randomUUID().toString
        val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(id).build()
        when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

        val result: Either[Nothing, String] = addApiHandler.handle(inputBody, mockContext)

        result.isRight shouldBe true
        val Right(responseEvent) = result
        val response: APIGatewayProxyResponseEvent = fromJson[APIGatewayProxyResponseEvent](responseEvent)
        response.getStatusCode shouldEqual HTTP_OK
        response.getBody shouldEqual s"""{"restApiId":"$id"}"""
      }

      "correctly convert OpenAPI JSON into ImportRestApiRequest with correct configuration" in new Setup {
        val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(UUID.randomUUID().toString).build()
        val importRestApiRequestCaptor: ArgumentCaptor[ImportRestApiRequest] = ArgumentCaptor.forClass(classOf[ImportRestApiRequest])
        when(mockAPIGatewayClient.importRestApi(importRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)
        val swagger: Swagger = new Swagger().host("localhost")
        when(mockSwaggerService.swagger(any[APIGatewayProxyRequestEvent])).thenReturn(swagger)

        val result: Either[Nothing, String] = addApiHandler.handle(inputBody, mockContext)

        val capturedRequest: ImportRestApiRequest = importRestApiRequestCaptor.getValue
        capturedRequest.parameters should contain(Entry("endpointConfigurationTypes", "REGIONAL"))
        capturedRequest.failOnWarnings shouldBe true
        capturedRequest.body shouldEqual fromUtf8String(toJson(swagger))
      }

      "deploy API" in new Setup {
        val apiId: String = UUID.randomUUID().toString
        val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(apiId).build()
        when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

        addApiHandler.handle(inputBody, mockContext)

        verify(mockDeploymentService, times(1)).deployApi(apiId)
      }

      "correctly handle UnauthorizedException thrown by AWS SDK when importing API" in new Setup {
        val errorMessage = "You're an idiot"
        when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

        val result: Either[Nothing, String] = addApiHandler.handle(inputBody, mockContext)

        result.isRight shouldBe true
        val Right(responseEvent) = result
        val response: APIGatewayProxyResponseEvent = fromJson[APIGatewayProxyResponseEvent](responseEvent)
        response.getStatusCode shouldEqual HTTP_INTERNAL_ERROR
        response.getBody shouldEqual errorMessage
      }
    }
  }
}

case class InputBody(httpMethod: String = "POST", host: String = "api-example-microservice.protected.mdtp", apiId: String = "") {
  override val toString: String = raw"""{
                            |    "httpMethod": "$httpMethod",
                            |    "pathParameters": {
                            |        "api_id": "$apiId"
                            |    },
                            |    "requestContext": {
                            |        "identity": {
                            |            "sourceIp": "127.0.0.1"
                            |        }
                            |    },
                            |    "body": "{\"host\": \"$host\", \"paths\": {\"/world\": {\"get\": {\"responses\": {\"200\": {\"description\": \"OK\"}},\"x-auth-type\": \"Application User\",\"x-throttling-tier\": \"Unlimited\",\"x-scope\": \"read:state-pension-calculation\"}}},\"info\": {\"title\": \"Test OpenAPI 2\",\"version\": \"1.0\"},\"swagger\": \"2.0\"}"
                            |}""".stripMargin
}
