package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.{HTTP_BAD_REQUEST, HTTP_OK}

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.github.mkotsur.aws.handler.Lambda
import io.github.mkotsur.aws.handler.Lambda._
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.PutMode.OVERWRITE
import software.amazon.awssdk.services.apigateway.model.{PutRestApiResponse, _}
import uk.gov.hmrc.apiplatform.addapi.AwsApiGatewayClient.awsApiGatewayClient
import uk.gov.hmrc.apiplatform.addapi.ErrorRecovery.recovery

import scala.collection.JavaConversions.mapAsJavaMap
import scala.util.Try

class AddApiHandler(apiGatewayClient: ApiGatewayClient,
                    deploymentService: DeploymentService,
                    swaggerService: SwaggerService)
  extends Lambda[String, String] with JsonMapper {

  val restApiIdResponse: String => Either[Nothing, String] = (restApiId: String) => {
    Right(toJson(new APIGatewayProxyResponseEvent()
      .withStatusCode(HTTP_OK)
      .withBody(toJson(AddApiResponse(restApiId)))
    ))
  }

  def this() {
    this(awsApiGatewayClient, new DeploymentService(awsApiGatewayClient), new SwaggerService)
  }

  override def handle(input: String, context: Context): Either[Nothing, String] = {
    val logger: LambdaLogger = context.getLogger
    logger.log(s"Input: $input")
    Try(handleInput(input)) recover recovery get
  }

  def handleInput(input: String): Either[Nothing, String] = {
    val requestEvent: APIGatewayProxyRequestEvent = fromJson[APIGatewayProxyRequestEvent](input)
    requestEvent.getHttpMethod match {
      case "POST" => restApiIdResponse(importApi(requestEvent))
      case "PUT" => restApiIdResponse(putApi(requestEvent))
      case _ => Right(toJson(new APIGatewayProxyResponseEvent().withStatusCode(HTTP_BAD_REQUEST).withBody("Unsupported Method")))
    }
  }

  def importApi(requestEvent: APIGatewayProxyRequestEvent): String = {
    val importApiRequest: ImportRestApiRequest = ImportRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swaggerService.swagger(requestEvent))))
      .parameters(mapAsJavaMap(Map("endpointConfigurationTypes" -> "REGIONAL")))
      .failOnWarnings(true)
      .build()

    val importRestApiResponse = apiGatewayClient.importRestApi(importApiRequest)
    deploymentService.deployApi(importRestApiResponse.id())
    importRestApiResponse.id()
  }

  def putApi(requestEvent: APIGatewayProxyRequestEvent): String = {
    val putApiRequest: PutRestApiRequest = PutRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swaggerService.swagger(requestEvent))))
      .parameters(mapAsJavaMap(Map("endpointConfigurationTypes" -> "REGIONAL")))
      .failOnWarnings(true)
      .mode(OVERWRITE)
      .restApiId(requestEvent.getPathParameters.get("api_id"))
      .build()

    val putRestApiResponse: PutRestApiResponse = apiGatewayClient.putRestApi(putApiRequest)
    deploymentService.deployApi(putRestApiResponse.id())
    putRestApiResponse.id()
  }
}

case class AddApiResponse(restApiId: String)

object AwsApiGatewayClient {
  lazy val awsApiGatewayClient: ApiGatewayClient = ApiGatewayClient.create()
}
