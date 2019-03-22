package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.{HTTP_INTERNAL_ERROR, HTTP_OK}

import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.circe.generic.auto._
import io.github.mkotsur.aws.handler.Lambda
import io.github.mkotsur.aws.handler.Lambda._
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.ImportRestApiRequest

import scala.util.{Failure, Success, Try}


class AddApiHandler(apiGatewayClient: ApiGatewayClient) extends Lambda[APIGatewayRequestEvent, APIGatewayResponseEvent] {

  def this() {
    this(ApiGatewayClient.create())
  }

  override def handle(input: APIGatewayRequestEvent, context: Context): Either[Nothing, APIGatewayResponseEvent] = {
    val logger: LambdaLogger = context.getLogger
    logger.log(s"Input: $input")
    val importApiRequest = ImportRestApiRequest.builder().body(SdkBytes.fromUtf8String(input.getBody)).build()
    val apiGatewayResponse = Try(apiGatewayClient.importRestApi(importApiRequest))

    apiGatewayResponse match {
      case Success(response) => Right(APIGatewayResponseEvent().withStatusCode(HTTP_OK).withBody(response.id()))
      case Failure(exception) => Right(APIGatewayResponseEvent().withStatusCode(HTTP_INTERNAL_ERROR).withBody(exception.getMessage))
    }
  }
}
