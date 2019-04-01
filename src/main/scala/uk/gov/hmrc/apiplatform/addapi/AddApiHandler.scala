package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.HTTP_OK

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.ImportRestApiRequest
import uk.gov.hmrc.api_platform_manage_api.{DeploymentService, SwaggerService}
import uk.gov.hmrc.api_platform_manage_api.AwsApiGatewayClient.awsApiGatewayClient
import uk.gov.hmrc.api_platform_manage_api.ErrorRecovery.recovery
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.ProxiedRequestHandler

import scala.collection.JavaConversions.mapAsJavaMap
import scala.language.postfixOps
import scala.util.Try

class AddApiHandler(apiGatewayClient: ApiGatewayClient,
                    deploymentService: DeploymentService,
                    swaggerService: SwaggerService)
  extends ProxiedRequestHandler {

  def this() {
    this(awsApiGatewayClient, new DeploymentService(awsApiGatewayClient), new SwaggerService)
  }

  override def handleInput(input: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    Try(importApi(input)) recover recovery get
  }

  def importApi(requestEvent: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val importApiRequest: ImportRestApiRequest = ImportRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swaggerService.createSwagger(requestEvent))))
      .parameters(mapAsJavaMap(Map("endpointConfigurationTypes" -> "REGIONAL")))
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
