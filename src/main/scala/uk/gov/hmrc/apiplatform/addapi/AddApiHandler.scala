package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.HTTP_OK

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.github.mkotsur.aws.handler.Lambda
import io.github.mkotsur.aws.handler.Lambda._
import io.swagger.models.{HttpMethod, Operation, Swagger}
import io.swagger.parser.SwaggerParser
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.Op.REPLACE
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.apiplatform.addapi.ErrorRecovery.recovery

import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.matching.Regex

class AddApiHandler(apiGatewayClient: ApiGatewayClient, environment: Map[String, String]) extends Lambda[String, String] with JsonMapper {

  val serviceNameRegex: Regex = "(.+)\\.protected\\.mdtp".r

  def this() {
    this(ApiGatewayClient.create(), sys.env)
  }

  override def handle(input: String, context: Context): Either[Nothing, String] = {
    val logger: LambdaLogger = context.getLogger
    logger.log(s"Input: $input")

    Try(importApi(input)) map { response =>
      Right(toJson(new APIGatewayProxyResponseEvent()
        .withStatusCode(HTTP_OK)
        .withBody(toJson(AddApiResponse(response.id())))
      ))
    } recover recovery get
  }

  def importApi(input: String): ImportRestApiResponse = {
    val importRestApiResponse = apiGatewayClient.importRestApi(buildImportApiRequest(input))
    apiGatewayClient.createDeployment(buildCreateDeploymentRequest(importRestApiResponse.id()))
    apiGatewayClient.updateStage(buildUpdateStageRequest(importRestApiResponse.id()))
    importRestApiResponse
  }

  def buildImportApiRequest(input: String): ImportRestApiRequest = {
    ImportRestApiRequest.builder()
      .body(fromUtf8String(toJson(swagger(fromJson[APIGatewayProxyRequestEvent](input)))))
      .parameters(mapAsJavaMap(Map("endpointConfigurationTypes" -> "REGIONAL")))
      .failOnWarnings(true)
      .build()
  }

  def buildCreateDeploymentRequest(restApiId: String): CreateDeploymentRequest = {
    CreateDeploymentRequest
      .builder()
      .restApiId(restApiId)
      .stageName("current")
      .build()
  }

  def buildUpdateStageRequest(restApiId: String): UpdateStageRequest = {
    UpdateStageRequest
      .builder()
      .restApiId(restApiId)
      .stageName("current")
      .patchOperations(PatchOperation.builder().op(REPLACE).path("/*/*/logging/loglevel").value("INFO").build())
      .build()
  }

  def swagger(requestEvent: APIGatewayProxyRequestEvent): Swagger = {
    val swagger: Swagger = new SwaggerParser().parse(requestEvent.getBody)
    swagger.getPaths.asScala foreach { path =>
      path._2.getOperationMap.asScala foreach { op =>
        op._2.setVendorExtension("x-amazon-apigateway-integration", amazonApigatewayIntegration(swagger.getHost, path._1, op))
      }
    }
    swagger.vendorExtension("x-amazon-apigateway-policy", amazonApigatewayPolicy(requestEvent))
  }

  def amazonApigatewayIntegration(host: String, path: String, operation: (HttpMethod, Operation)): Map[String, Object] = {
    serviceNameRegex.findFirstMatchIn(host) match {
      case Some(serviceNameMatch) =>
        Map("uri" -> s"https://${serviceNameMatch.group(1)}.${environment("domain")}$path",
        "responses" -> Map("default" -> Map("statusCode" -> "200")),
        "passthroughBehavior" -> "when_no_match",
        "connectionType" -> "VPC_LINK",
        "connectionId" -> environment("vpc_link_id"),
        "httpMethod" -> operation._1.name,
        "type" -> "http_proxy")
      case None => throw new RuntimeException("Invalid host format")
    }
  }

  def amazonApigatewayPolicy(requestEvent: APIGatewayProxyRequestEvent): Map[String, Object] = {
    Map("Version" -> "2012-10-17",
      "Statement" -> List(
        Map("Effect" -> "Allow", "Principal" -> "*", "Action" -> "execute-api:Invoke", "Resource" -> "*", "Condition" ->
          Map("IpAddress" ->
            Map("aws:SourceIp" -> s"${requestEvent.getRequestContext.getIdentity.getSourceIp}/32")
          )
        )
      )
    )
  }
}

case class AddApiResponse(restApiId: String)
