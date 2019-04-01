package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection._

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import software.amazon.awssdk.services.apigateway.model._

object ErrorRecovery {

  val TooManyRequests: Int = 429

  def recovery: PartialFunction[Throwable, APIGatewayProxyResponseEvent] = {
    case e: UnauthorizedException => exceptionResponse(HTTP_UNAUTHORIZED, e)
    case e: LimitExceededException => exceptionResponse(TooManyRequests, e)
    case e: BadRequestException => exceptionResponse(HTTP_BAD_REQUEST, e)
    case e: TooManyRequestsException => exceptionResponse(TooManyRequests, e)
    case e: ConflictException => exceptionResponse(HTTP_CONFLICT, e)
    case e: ServiceUnavailableException => exceptionResponse(HTTP_UNAVAILABLE, e)
    case e: NotFoundException => exceptionResponse(HTTP_NOT_FOUND, e)

    // Allow AwsServiceException, SdkClientException and ApiGatewayException to fall through and return 500
    case e: Throwable => exceptionResponse(HTTP_INTERNAL_ERROR, e)
  }

  def exceptionResponse(statusCode: Int, exception: Throwable): APIGatewayProxyResponseEvent =
    new APIGatewayProxyResponseEvent().withStatusCode(statusCode).withBody(exception.getMessage)

}
