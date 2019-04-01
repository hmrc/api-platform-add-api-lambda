package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection._

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.apiplatform.addapi.ErrorRecovery.TooManyRequests

class ErrorRecoverySpec extends WordSpecLike with Matchers with MockitoSugar {

  val errorMessage = "something went wrong"
  val errors: Map[Exception, Int] = Map(
    UnauthorizedException.builder().message(errorMessage).build() -> HTTP_UNAUTHORIZED,
    LimitExceededException.builder().message(errorMessage).build() -> TooManyRequests,
    BadRequestException.builder().message(errorMessage).build() -> HTTP_BAD_REQUEST,
    TooManyRequestsException.builder().message(errorMessage).build() -> TooManyRequests,
    ConflictException.builder().message(errorMessage).build() -> HTTP_CONFLICT,
    ServiceUnavailableException.builder().message(errorMessage).build() -> HTTP_UNAVAILABLE,
    NotFoundException.builder().message(errorMessage).build() -> HTTP_NOT_FOUND,
    new RuntimeException(errorMessage) -> HTTP_INTERNAL_ERROR
  )

  "error recovery" should {
    errors foreach { ex =>
      s"handle ${ex._1.getClass.getSimpleName}" in {
        val responseEvent: APIGatewayProxyResponseEvent = ErrorRecovery.recovery(ex._1)
        responseEvent should have('statusCode (ex._2), 'body (errorMessage))
      }
    }
  }
}
