package uk.gov.hmrc.apiplatform.addapi

import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import io.swagger.models.Swagger
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.Op.REPLACE
import software.amazon.awssdk.services.apigateway.model.PutMode.OVERWRITE
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.api_platform_manage_api.AwsApiGatewayClient.awsApiGatewayClient
import uk.gov.hmrc.api_platform_manage_api.{AwsIdRetriever, DeploymentService, SwaggerService}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.SqsHandler

import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.JavaConverters._
import scala.language.postfixOps

class UpsertApiHandler(override val apiGatewayClient: ApiGatewayClient,
                       deploymentService: DeploymentService,
                       swaggerService: SwaggerService,
                       environment: Map[String, String])
  extends SqsHandler with AwsIdRetriever {

  def this() {
    this(awsApiGatewayClient, new DeploymentService(awsApiGatewayClient), new SwaggerService, sys.env)
  }

  override def handleInput(input: SQSEvent, context: Context): Unit = {
    val logger: LambdaLogger = context.getLogger
    if (input.getRecords.size != 1) {
      throw new IllegalArgumentException(s"Invalid number of records: ${input.getRecords.size}")
    }

    val swagger: Swagger = swaggerService.createSwagger(input.getRecords.get(0).getBody)
    logger.log(s"Created swagger: ${toJson(swagger)}")
    getAwsRestApiIdByApiName(swagger.getInfo.getTitle) match {
      case Some(restApiId) => putApi(restApiId, swagger)
      case None => importApi(swagger)
    }
  }

  private def putApi(restApiId: String, swagger: Swagger): Unit = {

    val putApiRequest: PutRestApiRequest = PutRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swagger)))
      .failOnWarnings(true)
      .mode(OVERWRITE)
      .restApiId(restApiId)
      .build()

    val putRestApiResponse: PutRestApiResponse = apiGatewayClient.putRestApi(putApiRequest)
    ensureEndpointType(restApiId)
    deploymentService.deployApi(putRestApiResponse.id())
  }

  private def ensureEndpointType(restApiId: String): Unit = {
    val endpointType: String = environment.getOrElse("endpoint_type", "PRIVATE")

    val restApi = apiGatewayClient.getRestApi(GetRestApiRequest.builder().restApiId(restApiId).build())
    val currentEndpointType: String = restApi.endpointConfiguration().typesAsStrings().asScala.head

    if (currentEndpointType != endpointType) {
      val updateRestApiRequest: UpdateRestApiRequest = UpdateRestApiRequest
        .builder()
        .restApiId(restApiId)
        .patchOperations(PatchOperation
          .builder()
          .op(REPLACE)
          .path(s"/endpointConfiguration/types/$currentEndpointType")
          .value(endpointType)
          .build())
        .build()
      apiGatewayClient.updateRestApi(updateRestApiRequest)
    }
  }

  private def importApi(swagger: Swagger): Unit = {
    val importApiRequest: ImportRestApiRequest = ImportRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swagger)))
      .parameters(mapAsJavaMap(Map("endpointConfigurationTypes" -> environment.getOrElse("endpoint_type", "PRIVATE"))))
      .failOnWarnings(true)
      .build()

    val importRestApiResponse = apiGatewayClient.importRestApi(importApiRequest)
    deploymentService.deployApi(importRestApiResponse.id())
  }
}
