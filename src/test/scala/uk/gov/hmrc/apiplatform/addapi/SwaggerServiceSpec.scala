package uk.gov.hmrc.apiplatform.addapi

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import io.swagger.models.Swagger
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.collection.JavaConverters._

class SwaggerServiceSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
    val inputBody: String = InputBody().toString
    val environment: Map[String, String] = Map(
      "domain" -> "integration.tax.service.gov.uk",
      "vpc_link_id" -> "gix6s7"
    )
    val swaggerService = new SwaggerService(environment)
  }

  "swagger" should {

    "add amazon extension for API gateway policy" in new Setup {
      val swagger: Swagger = swaggerService.swagger(fromJson[APIGatewayProxyRequestEvent](inputBody))

      swagger.getVendorExtensions should contain key "x-amazon-apigateway-policy"
    }

    "correctly convert OpenAPI JSON into ImportRestApiRequest with amazon extension for API gateway responses" in new Setup {
      val swagger: Swagger = swaggerService.swagger(fromJson[APIGatewayProxyRequestEvent](inputBody))

      swagger.getVendorExtensions should contain key "x-amazon-apigateway-gateway-responses"
    }

    "correctly convert OpenAPI JSON into ImportRestApiRequest with amazon extensions for API gateway integrations" in new Setup {
      val swagger: Swagger = swaggerService.swagger(fromJson[APIGatewayProxyRequestEvent](inputBody))

      swagger.getPaths.asScala foreach { path =>
        path._2.getOperations.asScala foreach { op =>
          val vendorExtensions = op.getVendorExtensions.asScala
          vendorExtensions.keys should contain("x-amazon-apigateway-integration")
          vendorExtensions("x-amazon-apigateway-integration") match {
            case ve: Map[String, Object] =>
              ve("uri") shouldEqual "https://api-example-microservice.integration.tax.service.gov.uk/world"
              ve("connectionId") shouldEqual "gix6s7"
              ve("httpMethod") shouldEqual "GET"
            case _ => throw new ClassCastException
          }
        }
      }
    }

    "handle a host with an incorrect format" in new Setup {
      val ex: Exception = intercept[Exception] {
        swaggerService.swagger(fromJson[APIGatewayProxyRequestEvent](InputBody(host = "api-example-microservice").toString))
      }
      ex.getMessage shouldEqual "Invalid host format"
    }
  }
}
