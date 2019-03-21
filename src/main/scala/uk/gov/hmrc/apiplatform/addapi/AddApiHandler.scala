package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.HTTP_OK

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import io.github.mkotsur.aws.handler.Lambda
import io.github.mkotsur.aws.handler.Lambda._
import io.circe.generic.auto._
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.ImportRestApiRequest


class AddApiHandler(apiGatewayClient: ApiGatewayClient) extends Lambda[APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent] {

  def this() {
    this(ApiGatewayClient.create())
  }

  override def handle(input: APIGatewayProxyRequestEvent, context: Context): Either[Nothing, APIGatewayProxyResponseEvent] = {
    val importApiRequest = ImportRestApiRequest.builder().body(SdkBytes.fromUtf8String(input.getBody)).build()
    val apiGatewayResponse = apiGatewayClient.importRestApi(importApiRequest)

    Right(new APIGatewayProxyResponseEvent().withStatusCode(HTTP_OK).withBody(apiGatewayResponse.id()))
  }
}
