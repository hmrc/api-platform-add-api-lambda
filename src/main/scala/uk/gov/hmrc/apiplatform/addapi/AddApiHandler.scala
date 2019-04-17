package uk.gov.hmrc.apiplatform.addapi

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.api_platform_manage_api.AwsApiGatewayClient.awsApiGatewayClient
import uk.gov.hmrc.api_platform_manage_api.{DeploymentService, SwaggerService}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.SqsHandler

import scala.collection.JavaConversions.{mapAsJavaMap, _}
import scala.language.postfixOps

class AddApiHandler(apiGatewayClient: ApiGatewayClient,
                    deploymentService: DeploymentService,
                    swaggerService: SwaggerService,
                    environment: Map[String, String])
  extends SqsHandler {

  def this() {
    this(awsApiGatewayClient, new DeploymentService(awsApiGatewayClient), new SwaggerService, sys.env)
  }

  override def handleInput(input: SQSEvent, context: Context): Unit = {
    val importApiRequest: ImportRestApiRequest = ImportRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swaggerService.createSwagger(input.getRecords.toList.head.getBody))))
      .parameters(mapAsJavaMap(Map("endpointConfigurationTypes" -> environment.getOrElse("endpoint_type", "PRIVATE"))))
      .failOnWarnings(true)
      .build()

    val importRestApiResponse = apiGatewayClient.importRestApi(importApiRequest)
    deploymentService.deployApi(importRestApiResponse.id())
  }
}
