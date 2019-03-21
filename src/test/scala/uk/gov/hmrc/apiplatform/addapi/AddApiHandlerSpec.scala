package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection
import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.amazonaws.services.lambda.runtime.Context
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.{ImportRestApiRequest, ImportRestApiResponse}

class AddApiHandlerSpec extends FlatSpec with Matchers with MockitoSugar {
  trait Setup {
    val mockAPIGatewayClient = mock[ApiGatewayClient]
    val addApiHandler = new AddApiHandler(mockAPIGatewayClient)
  }

  "The Add API handler" should "send API specification to AWS endpoint" in new Setup {
    val id = UUID.randomUUID().toString
    val apiGatewayResponse = ImportRestApiResponse.builder().id(id).build()

    when(mockAPIGatewayClient.importRestApi(any[ImportRestApiRequest])).thenReturn(apiGatewayResponse)

    val result: Either[Nothing, APIGatewayProxyResponseEvent] = addApiHandler.handle(new APIGatewayProxyRequestEvent().withBody("{}"), mock[Context])

    result.isRight shouldBe true
    val Right(responseEvent) = result
    responseEvent.getStatusCode shouldEqual HttpURLConnection.HTTP_OK
    responseEvent.getBody shouldEqual id
  }
}
