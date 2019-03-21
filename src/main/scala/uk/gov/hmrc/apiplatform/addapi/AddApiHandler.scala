package uk.gov.hmrc.apiplatform.addapi

import com.amazonaws.services.lambda.runtime.Context
import io.github.mkotsur.aws.handler.Lambda
import io.github.mkotsur.aws.handler.Lambda._


class AddApiHandler extends Lambda[None.type, None.type] {
  override def handle(input: None.type, context: Context): Either[Nothing, None.type] = {
    Right(None)
  }
}
