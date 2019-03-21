package uk.gov.hmrc.apiplatform.addapi

import com.amazonaws.services.lambda.runtime.{ClientContext, CognitoIdentity, Context, LambdaLogger}
import org.scalatest._

class AddApiHandlerSpec extends FlatSpec with Matchers {
  trait Setup {
    val addApiHandler = new AddApiHandler()
  }
  
}

object TestContext extends Context {
  override def getAwsRequestId: String = ???
  override def getLogGroupName: String = ???
  override def getLogStreamName: String = ???
  override def getFunctionName: String = ???
  override def getFunctionVersion: String = ???
  override def getInvokedFunctionArn: String = ???
  override def getIdentity: CognitoIdentity = ???
  override def getClientContext: ClientContext = ???
  override def getRemainingTimeInMillis: Int = ???
  override def getMemoryLimitInMB: Int = ???
  override def getLogger: LambdaLogger = ???
}
