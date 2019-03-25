package uk.gov.hmrc.apiplatform.addapi

import java.net.HttpURLConnection.{HTTP_INTERNAL_ERROR, HTTP_OK}

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.github.mkotsur.aws.handler.Lambda
import io.github.mkotsur.aws.handler.Lambda._
import io.swagger.models.{HttpMethod, Operation, Swagger}
import io.swagger.parser.SwaggerParser
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.ImportRestApiRequest

import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class AddApiHandler(apiGatewayClient: ApiGatewayClient, environment: Map[String, String]) extends Lambda[String, String] with JsonMapper {

  def this() {
    this(ApiGatewayClient.create(), sys.env)
  }

  override def handle(input: String, context: Context): Either[Nothing, String] = {
    val logger: LambdaLogger = context.getLogger
    logger.log(s"Input: $input")

    val importApiRequest = ImportRestApiRequest.builder()
      .body(fromUtf8String(toJson(swagger(fromJson[APIGatewayProxyRequestEvent](input)))))
      .parameters(mapAsJavaMap(Map("endpointConfigurationTypes" -> "REGIONAL")))
      .failOnWarnings(true)
      .build()
    val apiGatewayResponse = Try(apiGatewayClient.importRestApi(importApiRequest))

    apiGatewayResponse match {
      case Success(response) => Right(toJson(new APIGatewayProxyResponseEvent().withStatusCode(HTTP_OK).withBody(response.id())))
      case Failure(exception) => Right(toJson(new APIGatewayProxyResponseEvent().withStatusCode(HTTP_INTERNAL_ERROR).withBody(exception.getMessage)))
    }
  }

  def swagger(requestEvent: APIGatewayProxyRequestEvent): Swagger = {
    val swagger: Swagger = new SwaggerParser().parse(requestEvent.getBody)
    swagger.getPaths.asScala foreach { path =>
      path._2.getOperationMap.asScala foreach { op =>
        op._2.setVendorExtension("x-amazon-apigateway-integration", amazonApigatewayIntegration(path._1, op))
      }
    }
    swagger.vendorExtension("x-amazon-apigateway-policy", amazonApigatewayPolicy(requestEvent))
  }

  def amazonApigatewayIntegration(path: String, op: (HttpMethod, Operation)): Map[String, Object] = {
    Map("uri" -> s"https://${environment("domain")}$path",
      "responses" -> Map("default" -> Map("statusCode" -> "200")),
      "passthroughBehavior" -> "when_no_match",
      "connectionType" -> "VPC_LINK",
      "connectionId" -> environment("vpc_link_id"),
      "httpMethod" -> op._1.name,
      "type" -> "http_proxy")
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
