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

import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.swagger.models.{Info, Swagger}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.mockito.{ArgumentCaptor, Mockito}
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.EndpointType.{PRIVATE, REGIONAL}
import software.amazon.awssdk.services.apigateway.model.Op.REPLACE
import software.amazon.awssdk.services.apigateway.model.PutMode.OVERWRITE
import software.amazon.awssdk.services.apigateway.model._
import software.amazon.awssdk.services.waf.model.DisassociateWebAclRequest
import software.amazon.awssdk.services.waf.regional.WafRegionalClient

import uk.gov.hmrc.api_platform_manage_api.{AccessLogConfiguration, DeploymentService, NoCloudWatchLogging, SwaggerService}
import uk.gov.hmrc.api_platform_manage_api.utils.JsonMapper
import scala.jdk.CollectionConverters._
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar

class UpsertApiHandlerSpec extends AnyWordSpec with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
    val usagePlans: Map[String, String] = Map("BRONZE" -> "1", "SILVER" -> "2")
    val apiId: String = UUID.randomUUID().toString
    val apiNameWithoutVersion = "foo"
    val apiName = s"$apiNameWithoutVersion--1.0"
    val version = "1.0"
    val context = "a/context"
    val requestBody = s"""{"host": "localhost", "info": {"title": "$apiName"}}"""
    val message = new SQSMessage()
    message.setBody(requestBody)
    val sqsEvent = new SQSEvent()
    sqsEvent.setRecords(List(message).asJava)
    val loggingDestinationArn: String = "aws:arn:1234567890"

    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val mockUsagePlanService: UsagePlanService = mock[UsagePlanService]
    val mockWafRegionalClient: WafRegionalClient = mock[WafRegionalClient]
    val mockSwaggerService: SwaggerService = mock[SwaggerService]
    val mockDeploymentService: DeploymentService = mock[DeploymentService]
    val mockContext: Context = mock[Context]
    val mockLambdaLogger: LambdaLogger = mock[LambdaLogger]

    when(mockContext.getLogger).thenReturn(mockLambdaLogger)
    when(mockAPIGatewayClient.getRestApi(any[GetRestApiRequest]))
      .thenReturn(GetRestApiResponse.builder().endpointConfiguration(EndpointConfiguration.builder().types(REGIONAL).build()).build())
    when(mockAPIGatewayClient.getRestApis(any[GetRestApisRequest])).thenReturn(buildMatchingRestApisResponse(apiId, apiName))
    when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenReturn(GetUsagePlanResponse.builder().build())

    val swagger: Swagger = new Swagger()
      .host("localhost")
      .info(new Info().title(apiName).version(version))
      .basePath(s"/$context")
    when(mockSwaggerService.createSwagger(any[String])).thenReturn(swagger)
  }

  trait StandardSetup extends Setup {
    val environment: Map[String, String] =
      Map(
        "AWS_REGION" -> "eu-west-2",
        "endpoint_type" -> "REGIONAL",
        "usage_plans" -> toJson(usagePlans),
        "update_usage_plan_queue" -> "arn:queue",
        "access_log_arn" -> loggingDestinationArn)
    val updateApiHandler =
      new UpsertApiHandler(mockAPIGatewayClient, mockUsagePlanService, mockWafRegionalClient, mockDeploymentService, mockSwaggerService, environment, Clock.fixed(Instant.parse("2023-10-02T18:15:30.00Z"), ZoneId.of("UTC")))
  }

  trait SetupWithoutEndpointType extends Setup {
    val environment: Map[String, String] =
      Map(
        "AWS_REGION" -> "eu-west-2",
        "usage_plans" -> toJson(usagePlans),
        "update_usage_plan_queue" -> "arn:queue",
        "access_log_arn" -> loggingDestinationArn)
    val updateApiHandler =
      new UpsertApiHandler(mockAPIGatewayClient, mockUsagePlanService, mockWafRegionalClient, mockDeploymentService, mockSwaggerService, environment)
  }

  "Update API Handler" should {
    "send API specification to AWS endpoint" in new StandardSetup {
      val id: String = UUID.randomUUID().toString
      val apiGatewayResponse: PutRestApiResponse =
        PutRestApiResponse.builder()
          .id(id)
          .endpointConfiguration(EndpointConfiguration.builder().types(PRIVATE).build())
          .build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      updateApiHandler.handleInput(sqsEvent, mockContext)

      verify(mockAPIGatewayClient).putRestApi(any[PutRestApiRequest])
    }

    "set the swagger description to indicate the date the API was updated" in new StandardSetup {
      val id: String = UUID.randomUUID().toString
      val apiGatewayResponse: PutRestApiResponse =
        PutRestApiResponse.builder()
          .id(id)
          .endpointConfiguration(EndpointConfiguration.builder().types(PRIVATE).build())
          .build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      updateApiHandler.handleInput(sqsEvent, mockContext)

      swagger.getInfo.getDescription shouldEqual "Published at 2023-10-02T18:15:30Z"
    }

    "correctly convert request event into PutRestApiRequest with correct configuration" in new StandardSetup {
      val apiGatewayResponse: PutRestApiResponse =
        PutRestApiResponse.builder()
          .id(apiId)
          .endpointConfiguration(EndpointConfiguration.builder().types(PRIVATE).build())
          .build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      updateApiHandler.handleInput(sqsEvent, mockContext)

      val putRestApiRequestCaptor = ArgumentCaptor.forClass(classOf[PutRestApiRequest])
      verify(mockAPIGatewayClient).putRestApi(putRestApiRequestCaptor.capture)
      val capturedRequest: PutRestApiRequest = putRestApiRequestCaptor.getValue
      capturedRequest.failOnWarnings shouldBe true
      capturedRequest.body shouldEqual fromUtf8String(toJson(swagger))
      capturedRequest.mode() shouldEqual OVERWRITE
      capturedRequest.restApiId() shouldEqual apiId
    }

    "update the endpoint type if the new value doesn't match the current one in AWS" in new StandardSetup {
      val apiGatewayResponse: PutRestApiResponse =
        PutRestApiResponse.builder()
          .id(apiId)
          .endpointConfiguration(EndpointConfiguration.builder().types(PRIVATE).build())
          .build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)
      when(mockAPIGatewayClient.updateRestApi(any[UpdateRestApiRequest])).thenReturn(UpdateRestApiResponse.builder().build())

      updateApiHandler.handleInput(sqsEvent, mockContext)

      val updateRestApiRequestCaptor = ArgumentCaptor.forClass(classOf[UpdateRestApiRequest])
      verify(mockAPIGatewayClient).updateRestApi(updateRestApiRequestCaptor.capture)
      val capturedUpdateRequest: UpdateRestApiRequest = updateRestApiRequestCaptor.getValue
      val patchOperation: PatchOperation = capturedUpdateRequest.patchOperations().asScala.head
      patchOperation.op() shouldEqual REPLACE
      patchOperation.path() shouldEqual "/endpointConfiguration/types/PRIVATE"
      patchOperation.value() shouldEqual "REGIONAL"
    }

    "not update the endpoint type if the new value matches the current one in AWS" in new StandardSetup {
      val apiGatewayResponse: PutRestApiResponse =
        PutRestApiResponse.builder()
          .id(apiId)
          .endpointConfiguration(EndpointConfiguration.builder().types(REGIONAL).build())
          .build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      updateApiHandler.handleInput(sqsEvent, mockContext)

      verify(mockAPIGatewayClient, Mockito.never()).updateRestApi(any[UpdateRestApiRequest])
    }

    "default to PRIVATE if no endpoint type specified in the environment" in new SetupWithoutEndpointType {
      val apiGatewayResponse: PutRestApiResponse =
        PutRestApiResponse.builder()
          .id(apiId)
          .endpointConfiguration(EndpointConfiguration.builder().types(REGIONAL).build())
          .build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)
      when(mockAPIGatewayClient.updateRestApi(any[UpdateRestApiRequest])).thenReturn(UpdateRestApiResponse.builder().build())

      updateApiHandler.handleInput(sqsEvent, mockContext)

      val updateRestApiRequestCaptor = ArgumentCaptor.forClass(classOf[UpdateRestApiRequest])
      verify(mockAPIGatewayClient).updateRestApi(updateRestApiRequestCaptor.capture)
      val capturedUpdateRequest: UpdateRestApiRequest = updateRestApiRequestCaptor.getValue
      val patchOperation: PatchOperation = capturedUpdateRequest.patchOperations().asScala.head
      patchOperation.op() shouldEqual REPLACE
      patchOperation.path() shouldEqual "/endpointConfiguration/types/REGIONAL"
      patchOperation.value() shouldEqual "PRIVATE"
    }

    "deploy API" in new StandardSetup {
      val apiGatewayResponse: PutRestApiResponse =
        PutRestApiResponse.builder()
          .id(apiId)
          .endpointConfiguration(EndpointConfiguration.builder().types(PRIVATE).build())
          .build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      updateApiHandler.handleInput(sqsEvent, mockContext)

      verify(mockDeploymentService, times(1))
        .deployApi(apiId, context, version, NoCloudWatchLogging, AccessLogConfiguration(updateApiHandler.AccessLogFormat, loggingDestinationArn))
    }

    "disassociate the stage with the web ACL" in new StandardSetup {
      val apiGatewayResponse: PutRestApiResponse =
        PutRestApiResponse.builder()
          .id(apiId)
          .endpointConfiguration(EndpointConfiguration.builder().types(PRIVATE).build())
          .build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      updateApiHandler.handleInput(sqsEvent, mockContext)

      verify(mockWafRegionalClient, times(1))
        .disassociateWebACL(DisassociateWebAclRequest
          .builder()
          .resourceArn(s"arn:aws:apigateway:${environment("AWS_REGION")}::/restapis/$apiId/stages/current")
          .build()
        )
    }

    "add the API to usage plans" in new StandardSetup {
      val apiGatewayResponse: PutRestApiResponse =
        PutRestApiResponse.builder()
          .id(apiId)
          .endpointConfiguration(EndpointConfiguration.builder().types(PRIVATE).build())
          .build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      updateApiHandler.handleInput(sqsEvent, mockContext)

      verify(mockUsagePlanService).addApiToUsagePlans(apiId, apiNameWithoutVersion)(mockLambdaLogger)
    }

    "propagate UnauthorizedException thrown by AWS SDK when updating API" in new StandardSetup {
      val errorMessage = "You're an idiot"
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

      val exception: UnauthorizedException = intercept[UnauthorizedException](updateApiHandler.handleInput(sqsEvent, mockContext))
      exception.getMessage shouldEqual errorMessage
    }

    "throw exception if the event has no messages" in new StandardSetup {
      sqsEvent.setRecords(List().asJava)

      val exception: IllegalArgumentException = intercept[IllegalArgumentException](updateApiHandler.handleInput(sqsEvent, mockContext))
      exception.getMessage shouldEqual "Invalid number of records: 0"
    }

    "throw exception if the event has multiple messages" in new StandardSetup {
      sqsEvent.setRecords(List(message, message).asJava)

      val exception: IllegalArgumentException = intercept[IllegalArgumentException](updateApiHandler.handleInput(sqsEvent, mockContext))
      exception.getMessage shouldEqual "Invalid number of records: 2"
    }
  }

  "AccessLogFormat" should {
    "be a single line string" in new StandardSetup {
      updateApiHandler.AccessLogFormat should not include "\n"
      updateApiHandler.AccessLogFormat should not include "\r"
    }
  }

  def buildMatchingRestApisResponse(matchingId: String, matchingName: String): GetRestApisResponse = {
    GetRestApisResponse.builder()
      .items(RestApi.builder().id(matchingId).name(matchingName).build())
      .build()
  }
}
