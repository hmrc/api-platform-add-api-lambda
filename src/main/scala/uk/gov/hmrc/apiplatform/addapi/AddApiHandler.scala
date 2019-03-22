package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.{HTTP_INTERNAL_ERROR, HTTP_OK}

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.github.mkotsur.aws.handler.Lambda
import io.github.mkotsur.aws.handler.Lambda._
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.ImportRestApiRequest

import scala.util.{Failure, Success, Try}


class AddApiHandler(apiGatewayClient: ApiGatewayClient) extends Lambda[String, String] with JsonMapper {

  def this() {
    this(ApiGatewayClient.create())
  }

  override def handle(input: String, context: Context): Either[Nothing, String] = {
    val logger: LambdaLogger = context.getLogger
    logger.log(s"Input: $input")
    val importApiRequest = ImportRestApiRequest.builder().body(fromUtf8String(fromJson[APIGatewayProxyRequestEvent](input).getBody)).build()
    val apiGatewayResponse = Try(apiGatewayClient.importRestApi(importApiRequest))

    apiGatewayResponse match {
      case Success(response) => Right(toJson(new APIGatewayProxyResponseEvent().withStatusCode(HTTP_OK).withBody(response.id())))
      case Failure(exception) => Right(toJson(new APIGatewayProxyResponseEvent().withStatusCode(HTTP_INTERNAL_ERROR).withBody(exception.getMessage)))
    }
  }
}
