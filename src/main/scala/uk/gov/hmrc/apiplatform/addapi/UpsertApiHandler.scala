package uk.gov.hmrc.apiplatform.addapi

import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.swagger.models.Swagger
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.Op.REPLACE
import software.amazon.awssdk.services.apigateway.model.PutMode.OVERWRITE
import software.amazon.awssdk.services.apigateway.model._
import software.amazon.awssdk.services.waf.model.DisassociateWebAclRequest
import software.amazon.awssdk.services.waf.regional.WafRegionalClient
import uk.gov.hmrc.api_platform_manage_api.AwsApiGatewayClient.awsApiGatewayClient
import uk.gov.hmrc.api_platform_manage_api._
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.SqsHandler
import java.time.format.DateTimeFormatter
import java.time.LocalDate

import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.JavaConverters._
import scala.language.postfixOps
import java.time.LocalDateTime
import java.time.Clock
import java.time.ZoneId
import java.time.Instant

class UpsertApiHandler(override val apiGatewayClient: ApiGatewayClient,
                       usagePlanService: UsagePlanService,
                       wafRegionalClient: WafRegionalClient,
                       deploymentService: DeploymentService,
                       swaggerService: SwaggerService,
                       environment: Map[String, String])
  extends SqsHandler with AwsIdRetriever {

  val clock = Clock.systemUTC()

  val AccessLogFormat: String =
    """{
      |"apiKey": "$context.identity.apiKey",
      |"requestId": "$context.requestId",
      |"authorizer.clientId": "$context.authorizer.clientId",
      |"authorizer.apiVersion": "$context.authorizer.apiVersion",
      |"authorizer.apiContext": "$context.authorizer.apiContext",
      |"authorizer.authType": "$context.authorizer.authorisationType",
      |"authorizer.requestId": "$context.authorizer.requestId",
      |"authorizer.applicationId": "$context.authorizer.applicationId",
      |"httpMethod": "$context.httpMethod",
      |"clientIp": "$context.identity.sourceIp",
      |"requestTimeEpoch": "$context.requestTimeEpoch",
      |"resourcePath": "$context.resourcePath",
      |"status": "$context.status",
      |"extended.errorMessage": "$context.integration.error",
      |"path": "$context.path",
      |"extended.contextErrorResponseType": "$context.error.responseType",
      |"extended.contextErrorMessage": "$context.error.message"}""".stripMargin.replaceAll("[\n\r]","")

  def this() {
    this(awsApiGatewayClient, new UsagePlanService, WafRegionalClient.create(), new DeploymentService(awsApiGatewayClient), new SwaggerService, sys.env)
  }

  override def handleInput(input: SQSEvent, context: Context): Unit = {
    implicit val logger: LambdaLogger = context.getLogger
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

  private def putApi(restApiId: String, swagger: Swagger)(implicit logger: LambdaLogger): Unit = {

    // val clock = Clock.fixed(Instant.parse("2014-12-22T10:15:30.00Z"), ZoneId.of("UTC"));
    val formatDateTime = DateTimeFormatter.ISO_LOCAL_DATE
    swagger.getInfo().description("Updated by API Platform add-api-lambda at " + formatDateTime.format(LocalDateTime.now(clock)))

    val putApiRequest: PutRestApiRequest = PutRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swagger)))
      .failOnWarnings(true)
      .mode(OVERWRITE)
      .restApiId(restApiId)
      .build()

    logger.log(s"Updating API ${swagger.getInfo.getTitle}")
    val putRestApiResponse: PutRestApiResponse = apiGatewayClient.putRestApi(putApiRequest)
    logger.log(s"Ensuring endpoint type for API: ${swagger.getInfo.getTitle}")
    ensureEndpointType(restApiId, putRestApiResponse.endpointConfiguration(), environment.getOrElse("endpoint_type", "PRIVATE"))
    deployApi(putRestApiResponse.id(), swagger)
  }

  private def ensureEndpointType(restApiId: String,
                                 endpointConfiguration: EndpointConfiguration,
                                 requiredEndpointType: String)
                                (implicit logger: LambdaLogger): Unit = {
    val currentEndpointType: String = endpointConfiguration.typesAsStrings().asScala.head

    if (currentEndpointType != requiredEndpointType) {
      logger.log(s"Updating Endpoint Type from [$currentEndpointType] to [$requiredEndpointType]")
      val updateRestApiRequest: UpdateRestApiRequest = UpdateRestApiRequest
        .builder()
        .restApiId(restApiId)
        .patchOperations(PatchOperation
          .builder()
          .op(REPLACE)
          .path(s"/endpointConfiguration/types/$currentEndpointType")
          .value(requiredEndpointType)
          .build())
        .build()
      apiGatewayClient.updateRestApi(updateRestApiRequest)
    }
  }

  private def importApi(swagger: Swagger)(implicit logger: LambdaLogger): Unit = {
    swagger.getInfo().description("Created by API Platform add-api-lambda at " + System.currentTimeMillis())
    val importApiRequest: ImportRestApiRequest = ImportRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swagger)))
      .parameters(mapAsJavaMap(Map("endpointConfigurationTypes" -> environment.getOrElse("endpoint_type", "PRIVATE"))))
      .failOnWarnings(true)
      .build()

    logger.log(s"Importing API: ${swagger.getInfo.getTitle}")
    val importRestApiResponse = apiGatewayClient.importRestApi(importApiRequest)
    deployApi(importRestApiResponse.id(), swagger)
  }

  private def deployApi(restApiId: String, swagger: Swagger)(implicit logger: LambdaLogger): Unit = {
    val title = swagger.getInfo.getTitle
    val titleWithoutVersion = title.substring(0, title.lastIndexOf("--"))

    logger.log(s"Deploying API: $title")

    deploymentService.deployApi(
      restApiId,
      swagger.getBasePath.stripPrefix("/"),
      swagger.getInfo.getVersion,
      NoCloudWatchLogging,
      AccessLogConfiguration(AccessLogFormat, environment("access_log_arn")))

    ensureNoWebACL(restApiId)
    usagePlanService.addApiToUsagePlans(restApiId, titleWithoutVersion)
  }

  private def ensureNoWebACL(restApiId: String)(implicit logger: LambdaLogger): Unit = {
    val stageArn: String = s"arn:aws:apigateway:${environment("AWS_REGION")}::/restapis/$restApiId/stages/current"

    val request: DisassociateWebAclRequest = DisassociateWebAclRequest.builder().resourceArn(stageArn).build()
    wafRegionalClient.disassociateWebACL(request)
  }
}
