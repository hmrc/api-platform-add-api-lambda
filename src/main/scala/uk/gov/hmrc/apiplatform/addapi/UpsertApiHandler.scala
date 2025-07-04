/*
 * Copyright 2025 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.apiplatform.addapi

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.events.SQSEvent
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
import uk.gov.hmrc.api_platform_manage_api.utils.SqsHandler

import java.time.Clock
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._
import java.time.ZonedDateTime

class UpsertApiHandler(override val apiGatewayClient: ApiGatewayClient,
                       usagePlanService: UsagePlanService,
                       wafRegionalClient: WafRegionalClient,
                       deploymentService: DeploymentService,
                       swaggerService: SwaggerService,
                       environment: Map[String, String],
                       private val clock: Clock = Clock.systemUTC())
  extends SqsHandler with AwsIdRetriever {

  private val isoTimeFormatter = DateTimeFormatter.ISO_INSTANT

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
      |"extended.contextErrorMessage": "$context.error.message",
      |"apiId": "$context.apiId"}""".stripMargin.replaceAll("[\n\r]","")

  def this() = {
    this(awsApiGatewayClient, new UsagePlanService, WafRegionalClient.create(), new DeploymentService(awsApiGatewayClient), new SwaggerService, sys.env)
  }

  override def handleInput(input: SQSEvent, context: Context): Unit = {
    implicit val logger: LambdaLogger = context.getLogger
    if (input.getRecords.size != 1) {
      throw new IllegalArgumentException(s"Invalid number of records: ${input.getRecords.size}")
    }

    val swagger: Swagger = swaggerService.createSwagger(input.getRecords.get(0).getBody)
    
    swagger.getInfo.description("Published at " + isoTimeFormatter.format(ZonedDateTime.now(clock)))
    logger.log(s"Created swagger: ${toJson(swagger)}")
    getAwsRestApiIdByApiName(swagger.getInfo.getTitle) match {
      case Some(restApiId) => putApi(restApiId, swagger)
      case None => importApi(swagger)
    }
  }

  private def putApi(restApiId: String, swagger: Swagger)(implicit logger: LambdaLogger): Unit = {
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
    val importApiRequest: ImportRestApiRequest = ImportRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swagger)))
      .parameters(Map("endpointConfigurationTypes" -> environment.getOrElse("endpoint_type", "PRIVATE")).asJava)
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

  private def ensureNoWebACL(restApiId: String): Unit = {
    val stageArn: String = s"arn:aws:apigateway:${environment("AWS_REGION")}::/restapis/$restApiId/stages/current"

    val request: DisassociateWebAclRequest = DisassociateWebAclRequest.builder().resourceArn(stageArn).build()
    wafRegionalClient.disassociateWebACL(request)
  }
}
