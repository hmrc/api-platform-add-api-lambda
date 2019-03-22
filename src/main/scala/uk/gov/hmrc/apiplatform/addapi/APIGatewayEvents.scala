package uk.gov.hmrc.apiplatform.addapi

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}

case class APIGatewayRequestEvent() extends APIGatewayProxyRequestEvent {
  override def withBody(body: String): APIGatewayRequestEvent = {
    super.withBody(body)
    this
  }
}

case class APIGatewayResponseEvent() extends APIGatewayProxyResponseEvent {
  override def withStatusCode(statusCode: Integer): APIGatewayResponseEvent = {
    super.withStatusCode(statusCode)
    this
  }

  override def withBody(body: String): APIGatewayResponseEvent = {
    super.withBody(body)
    this
  }
}
