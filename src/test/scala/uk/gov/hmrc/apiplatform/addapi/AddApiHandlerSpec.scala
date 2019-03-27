package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.{HTTP_INTERNAL_ERROR, HTTP_OK}
import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.swagger.models.Swagger
import io.swagger.parser.SwaggerParser
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.Op.REPLACE
import software.amazon.awssdk.services.apigateway.model._

import scala.collection.JavaConverters._

class AddApiHandlerSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {
  trait Setup {
    val inputBody: String = InputBody().toString
    val mockLambdaLogger: LambdaLogger = mock[LambdaLogger]
    val mockContext: Context = mock[Context]
    when(mockContext.getLogger).thenReturn(mockLambdaLogger)
    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val environment: Map[String, String] = Map(
      "domain" -> "integration.tax.service.gov.uk",
      "vpc_link_id" -> "gix6s7"
    )
    val addApiHandler = new AddApiHandler(mockAPIGatewayClient, environment)
  }

  "The Add API handler" should {
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

    "correctly convert OpenAPI JSON into ImportRestApiRequest with amazon extension for API gateway policy" in new Setup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(UUID.randomUUID().toString).build()
      val importRestApiRequestCaptor: ArgumentCaptor[ImportRestApiRequest] = ArgumentCaptor.forClass(classOf[ImportRestApiRequest])
      when(mockAPIGatewayClient.importRestApi(importRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)

      val result: Either[Nothing, String] = addApiHandler.handle(inputBody, mockContext)

      val capturedRequest: ImportRestApiRequest = importRestApiRequestCaptor.getValue
      capturedRequest.body().asUtf8String() should include("x-amazon-apigateway-policy")
      capturedRequest.parameters should contain (Entry("endpointConfigurationTypes", "REGIONAL"))
      capturedRequest.failOnWarnings shouldBe true
    }

    "correctly convert OpenAPI JSON into ImportRestApiRequest with amazon extension for API gateway responses" in new Setup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(UUID.randomUUID().toString).build()
      val importRestApiRequestCaptor: ArgumentCaptor[ImportRestApiRequest] = ArgumentCaptor.forClass(classOf[ImportRestApiRequest])
      when(mockAPIGatewayClient.importRestApi(importRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)

      val result: Either[Nothing, String] = addApiHandler.handle(inputBody, mockContext)

      val capturedRequest: ImportRestApiRequest = importRestApiRequestCaptor.getValue
      capturedRequest.body().asUtf8String() should include("x-amazon-apigateway-gateway-responses")
    }

    "correctly convert OpenAPI JSON into ImportRestApiRequest with amazon extensions for API gateway integrations" in new Setup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(UUID.randomUUID().toString).build()
      val importRestApiRequestCaptor: ArgumentCaptor[ImportRestApiRequest] = ArgumentCaptor.forClass(classOf[ImportRestApiRequest])
      when(mockAPIGatewayClient.importRestApi(importRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)

      val result: Either[Nothing, String] = addApiHandler.handle(inputBody, mockContext)

      val capturedRequest: ImportRestApiRequest = importRestApiRequestCaptor.getValue
      val swagger: Swagger = new SwaggerParser().parse(capturedRequest.body().asUtf8String())
      swagger.getPaths.asScala foreach { path =>
        path._2.getOperations.asScala foreach { op =>
          val vendorExtensions = op.getVendorExtensions.asScala
          vendorExtensions.keys should contain ("x-amazon-apigateway-integration")
          vendorExtensions("x-amazon-apigateway-integration") match {
            case jve: java.util.Map[String, Object] =>
              val ve = jve.asScala
              ve("uri") shouldEqual "https://api-example-microservice.integration.tax.service.gov.uk/world"
              ve("connectionId") shouldEqual "gix6s7"
              ve("httpMethod") shouldEqual "GET"
            case _ => throw new ClassCastException
          }
        }
      }
    }

    "handle a host with an incorrect format in the swagger payload" in new Setup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(UUID.randomUUID().toString).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

      val result: Either[Nothing, String] = addApiHandler.handle(InputBody(host = "api-example-microservice").toString, mockContext)

      result.isRight shouldBe true
      val Right(responseEvent) = result
      val response: APIGatewayProxyResponseEvent = fromJson[APIGatewayProxyResponseEvent](responseEvent)
      response.getStatusCode shouldEqual HTTP_INTERNAL_ERROR
      response.getBody shouldEqual "Invalid host format"
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

    "deploy the rest API that was previously imported" in new Setup {
      val importedRestApiId: String = UUID.randomUUID().toString
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(importedRestApiId).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)
      val createDeploymentRequestCaptor: ArgumentCaptor[CreateDeploymentRequest] = ArgumentCaptor.forClass(classOf[CreateDeploymentRequest])
      when(mockAPIGatewayClient.createDeployment(createDeploymentRequestCaptor.capture())).thenReturn(CreateDeploymentResponse.builder().build())

      val result: Either[Nothing, String] = addApiHandler.handle(inputBody, mockContext)

      val capturedRequest: CreateDeploymentRequest = createDeploymentRequestCaptor.getValue
      capturedRequest.restApiId shouldEqual importedRestApiId
      capturedRequest.stageName shouldEqual "current"
    }

    "correctly handle UnauthorizedException thrown by AWS SDK when deploying API" in new Setup {
      val errorMessage = "You're an idiot"
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(ImportRestApiResponse.builder().id(UUID.randomUUID().toString).build())
      when(mockAPIGatewayClient.createDeployment(any[CreateDeploymentRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

      val result: Either[Nothing, String] = addApiHandler.handle(inputBody, mockContext)

      result.isRight shouldBe true
      val Right(responseEvent) = result
      val response: APIGatewayProxyResponseEvent = fromJson[APIGatewayProxyResponseEvent](responseEvent)
      response.getStatusCode shouldEqual HTTP_INTERNAL_ERROR
      response.getBody shouldEqual errorMessage
    }

    "update the stage with extra settings" in new Setup {
      val importedRestApiId: String = UUID.randomUUID().toString
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(importedRestApiId).build()
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)
      when(mockAPIGatewayClient.createDeployment(any[CreateDeploymentRequest])).thenReturn(CreateDeploymentResponse.builder().build())
      val updateStageRequestCaptor: ArgumentCaptor[UpdateStageRequest] = ArgumentCaptor.forClass(classOf[UpdateStageRequest])
      when(mockAPIGatewayClient.updateStage(updateStageRequestCaptor.capture())).thenReturn(UpdateStageResponse.builder().build())

      val result: Either[Nothing, String] = addApiHandler.handle(inputBody, mockContext)

      val capturedRequest: UpdateStageRequest = updateStageRequestCaptor.getValue
      capturedRequest.restApiId shouldEqual importedRestApiId
      capturedRequest.stageName shouldEqual "current"
      val operations = capturedRequest.patchOperations.asScala
      exactly(1, operations) should have('op(REPLACE), 'path("/*/*/logging/loglevel"), 'value("INFO"))
    }

    "correctly handle UnauthorizedException thrown by AWS SDK when updating stage with extra settings" in new Setup {
      val errorMessage = "You're an idiot"
      when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(ImportRestApiResponse.builder().id(UUID.randomUUID().toString).build())
      when(mockAPIGatewayClient.createDeployment(any[CreateDeploymentRequest])).thenReturn(CreateDeploymentResponse.builder().build())
      when(mockAPIGatewayClient.updateStage(any[UpdateStageRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

      val result: Either[Nothing, String] = addApiHandler.handle(inputBody, mockContext)

      result.isRight shouldBe true
      val Right(responseEvent) = result
      val response: APIGatewayProxyResponseEvent = fromJson[APIGatewayProxyResponseEvent](responseEvent)
      response.getStatusCode shouldEqual HTTP_INTERNAL_ERROR
      response.getBody shouldEqual errorMessage
    }
  }
}

case class InputBody(host: String = "api-example-microservice.protected.mdtp") {
  override val toString: String = raw"""{
                            |    "requestContext": {
                            |        "identity": {
                            |            "sourceIp": "127.0.0.1"
                            |        }
                            |    },
                            |    "body": "{\"host\": \"$host\", \"paths\": {\"/world\": {\"get\": {\"responses\": {\"200\": {\"description\": \"OK\"}},\"x-auth-type\": \"Application User\",\"x-throttling-tier\": \"Unlimited\",\"x-scope\": \"read:state-pension-calculation\"}}},\"info\": {\"title\": \"Test OpenAPI 2\",\"version\": \"1.0\"},\"swagger\": \"2.0\"}"
                            |}""".stripMargin
}
