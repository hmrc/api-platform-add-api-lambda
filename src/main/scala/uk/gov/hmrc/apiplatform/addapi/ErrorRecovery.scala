package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent

object ErrorRecovery extends JsonMapper {
  def recovery: PartialFunction[Throwable, Either[Nothing, String]] = {
    case e => Right(toJson(new APIGatewayProxyResponseEvent().withStatusCode(HTTP_INTERNAL_ERROR).withBody(e.getMessage)))
  }
}
