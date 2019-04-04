package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection._

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.api_platform_manage_api.AwsApiGatewayClient.awsApiGatewayClient
import uk.gov.hmrc.api_platform_manage_api.ErrorRecovery.recovery
import uk.gov.hmrc.api_platform_manage_api.{DeploymentService, SwaggerService}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.ProxiedRequestHandler

import scala.collection.JavaConversions.mapAsJavaMap
import scala.language.postfixOps
import scala.util.Try

class AddApiHandler(apiGatewayClient: ApiGatewayClient,
                    deploymentService: DeploymentService,
                    swaggerService: SwaggerService,
                    environment: Map[String, String])
  extends ProxiedRequestHandler {

  def this() {
    this(awsApiGatewayClient, new DeploymentService(awsApiGatewayClient), new SwaggerService, sys.env)
  }

  override def handleInput(input: APIGatewayProxyRequestEvent, context: Context): APIGatewayProxyResponseEvent = {
    Try(importApi(input)) recover recovery(context.getLogger) get
  }

  def importApi(requestEvent: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val importApiRequest: ImportRestApiRequest = ImportRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swaggerService.createSwagger(requestEvent))))
      .parameters(mapAsJavaMap(Map("endpointConfigurationTypes" -> environment.getOrElse("endpoint_type", "PRIVATE"))))
      .failOnWarnings(true)
      .build()

    val importRestApiResponse = apiGatewayClient.importRestApi(importApiRequest)
    deploymentService.deployApi(importRestApiResponse.id())

    new APIGatewayProxyResponseEvent()
      .withStatusCode(HTTP_OK)
      .withBody(toJson(AddApiResponse(importRestApiResponse.id())))
  }
}

case class AddApiResponse(restApiId: String)
