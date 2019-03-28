package uk.gov.hmrc.apiplatform.addapi

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import io.swagger.models.{HttpMethod, Operation, Swagger}
import io.swagger.parser.SwaggerParser

import scala.collection.JavaConverters._
import scala.util.matching.Regex

class SwaggerService(environment: Map[String, String]) {

  val serviceNameRegex: Regex = """(.+)\.protected\.mdtp""".r

  def this() {
    this(sys.env)
  }

  def swagger(requestEvent: APIGatewayProxyRequestEvent): Swagger = {
    val swagger: Swagger = new SwaggerParser().parse(requestEvent.getBody)
    swagger.getPaths.asScala foreach { path =>
      path._2.getOperationMap.asScala foreach { op =>
        op._2.setVendorExtension("x-amazon-apigateway-integration", amazonApigatewayIntegration(swagger.getHost, path._1, op))
      }
    }
    swagger.vendorExtension("x-amazon-apigateway-policy", amazonApigatewayPolicy(requestEvent))
    swagger.vendorExtension("x-amazon-apigateway-gateway-responses", amazonApigatewayResponses)
  }

  private def amazonApigatewayIntegration(host: String, path: String, operation: (HttpMethod, Operation)): Map[String, Object] = {
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

  private def amazonApigatewayPolicy(requestEvent: APIGatewayProxyRequestEvent): Map[String, Object] = {
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

  private def amazonApigatewayResponses: Map[String, Object] = {
    Map("MISSING_AUTHENTICATION_TOKEN" -> Map("statusCode" -> "404", "responseTemplates" ->
      Map("application/json" -> """{"code": "MATCHING_RESOURCE_NOT_FOUND", "message": "A resource with the name in the request can not be found in the API"}""")),
      "THROTTLED" -> Map("statusCode" -> "429", "responseTemplates" ->
        Map("application/json" -> """{"code": "MESSAGE_THROTTLED_OUT", "message", "The request for the API is throttled as you have exceeded your quota."}""")))
  }
}
