package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.{HTTP_INTERNAL_ERROR, HTTP_OK}
import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.{ImportRestApiRequest, ImportRestApiResponse, UnauthorizedException}

class AddApiHandlerSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {
  trait Setup {
    val inputBody = """{"body": "bar"}"""
    val mockLambdaLogger: LambdaLogger = mock[LambdaLogger]
    val mockContext: Context = mock[Context]
    when(mockContext.getLogger).thenReturn(mockLambdaLogger)
    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val addApiHandler = new AddApiHandler(mockAPIGatewayClient)
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
      response.getBody shouldEqual id
    }

    "correctly convert OpenAPI JSON into ImportRestApiRequest" in new Setup {
      val apiGatewayResponse: ImportRestApiResponse = ImportRestApiResponse.builder().id(UUID.randomUUID().toString).build()

      val importRestApiRequestCaptor: ArgumentCaptor[ImportRestApiRequest] = ArgumentCaptor.forClass(classOf[ImportRestApiRequest])
      when(mockAPIGatewayClient.importRestApi(importRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)

      val result: Either[Nothing, String] = addApiHandler.handle(inputBody, mockContext)

      val capturedRequest: ImportRestApiRequest = importRestApiRequestCaptor.getValue
      capturedRequest.body().asUtf8String() shouldEqual "bar"
    }

    "correctly handle UnauthorizedException thrown by AWS SDK" in new Setup {
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

case class Simple(msg: String)
