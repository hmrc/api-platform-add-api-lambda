package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.{HTTP_BAD_METHOD, HTTP_OK}

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.PutMode.OVERWRITE
import software.amazon.awssdk.services.apigateway.model.{PutRestApiResponse, _}
import uk.gov.hmrc.apiplatform.addapi.AwsApiGatewayClient.awsApiGatewayClient
import uk.gov.hmrc.apiplatform.addapi.ErrorRecovery.recovery
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.ProxiedRequestHandler

import scala.collection.JavaConversions.mapAsJavaMap
import scala.language.postfixOps
import scala.util.Try

class AddApiHandler(apiGatewayClient: ApiGatewayClient,
                    deploymentService: DeploymentService,
                    swaggerService: SwaggerService)
  extends ProxiedRequestHandler {

  val restApiIdResponse: String => APIGatewayProxyResponseEvent = (restApiId: String) => {
    new APIGatewayProxyResponseEvent()
      .withStatusCode(HTTP_OK)
      .withBody(toJson(AddApiResponse(restApiId)))
  }

  def this() {
    this(awsApiGatewayClient, new DeploymentService(awsApiGatewayClient), new SwaggerService)
  }

  override def handleInput(input: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    Try {
      input.getHttpMethod match {
        case "POST" => restApiIdResponse(importApi(input))
        case "PUT" => restApiIdResponse(putApi(input))
        case "DELETE" => restApiIdResponse(deleteApi(input))
        case _ => new APIGatewayProxyResponseEvent().withStatusCode(HTTP_BAD_METHOD).withBody("Unsupported Method")
      }
    } recover recovery get
  }

  def importApi(requestEvent: APIGatewayProxyRequestEvent): String = {
    val importApiRequest: ImportRestApiRequest = ImportRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swaggerService.createSwagger(requestEvent))))
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
      .body(fromUtf8String(toJson(swaggerService.createSwagger(requestEvent))))
      .parameters(mapAsJavaMap(Map("endpointConfigurationTypes" -> "REGIONAL")))
      .failOnWarnings(true)
      .mode(OVERWRITE)
      .restApiId(requestEvent.getPathParameters.get("api_id"))
      .build()

    val putRestApiResponse: PutRestApiResponse = apiGatewayClient.putRestApi(putApiRequest)
    deploymentService.deployApi(putRestApiResponse.id())
    putRestApiResponse.id()
  }

  def deleteApi(requestEvent: APIGatewayProxyRequestEvent): String = {
    val apiId = requestEvent.getPathParameters.get("api_id")
    val deleteApiRequest = DeleteRestApiRequest.builder().restApiId(apiId).build()
    apiGatewayClient.deleteRestApi(deleteApiRequest)
    apiId
  }
}

case class AddApiResponse(restApiId: String)

object AwsApiGatewayClient {
  lazy val awsApiGatewayClient: ApiGatewayClient = ApiGatewayClient.create()
}
