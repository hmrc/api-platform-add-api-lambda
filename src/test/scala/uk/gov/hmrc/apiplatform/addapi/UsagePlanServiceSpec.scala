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

import com.amazonaws.services.lambda.runtime.LambdaLogger

import org.mockito.captor.ArgCaptor
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.Op.{ADD, REMOVE}
import software.amazon.awssdk.services.apigateway.model._
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{SendMessageRequest, SendMessageResponse}

import uk.gov.hmrc.api_platform_manage_api.utils.JsonMapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.mockito.Strictness.Lenient

class UsagePlanServiceSpec extends AnyWordSpec with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
    val apiId: String = UUID.randomUUID().toString
    val apiNameWithoutVersion = "foo"
    val highPriorityApiNameWithoutVersion = "bar"
    val baseUsagePlans: Seq[String] = Seq("BRONZE", "SILVER")
    val apiPriority: Map[String, String] = Map(highPriorityApiNameWithoutVersion -> "HIGH")
    val usagePlans: Map[String, String] = Map("BRONZE" -> "1", "SILVER" -> "2", "BRONZE_HIGH" -> "3", "SILVER_HIGH" -> "4")

    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient](withSettings.strictness(Lenient))
    val mockSqsClient: SqsClient = mock[SqsClient](withSettings.strictness(Lenient))
    implicit val mockLambdaLogger: LambdaLogger = mock[LambdaLogger](withSettings.strictness(Lenient))
    doNothing.when(mockLambdaLogger).log(*[String])

    val environment: Map[String, String] = Map("base_usage_plans" -> toJson(baseUsagePlans),
      "api_priority" -> toJson(apiPriority), "usage_plans" -> toJson(usagePlans), "update_usage_plan_queue" -> "arn:queue")
    val usagePlanService = new UsagePlanService(mockAPIGatewayClient, mockSqsClient, environment)

    def verifyUsagePlanUpdate(updates: (String, String, Op)*): Unit = {
      updates foreach { update =>
        val capturedRequest: UsagePlanUpdateMsg = fromJson[UsagePlanUpdateMsg](update._1)
        capturedRequest.usagePlanId shouldBe usagePlans(update._2)
        capturedRequest.patchOperations should contain only PatchOp(update._3.toString, "/apiStages", s"$apiId:current")
      }
    }

    val builder = GetUsagePlanResponse.builder()

    def getUsagePlanAnswer(usagePlansIds: Seq[String]): InvocationOnMock => GetUsagePlanResponse = {
      invocationOnMock => {
        val request: GetUsagePlanRequest = invocationOnMock.getArgument(0)
        if (usagePlansIds.contains(request.usagePlanId)) {
          builder.apiStages(ApiStage.builder().apiId(apiId).stage("current").build()).build()
        } else {
          builder.apiStages(ApiStage.builder().build()).build()
        }
      }
    }
  }

  "Add API to usage plans" should {
    "only add the API to base usage plans when it's not a high priority API" in new Setup {
      when(mockSqsClient.sendMessage(any[SendMessageRequest])).thenReturn(SendMessageResponse.builder().build())
      when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenReturn(builder.build())

      usagePlanService.addApiToUsagePlans(apiId, apiNameWithoutVersion)

      val sendMessageRequestCaptor = ArgCaptor[SendMessageRequest]
      verify(mockSqsClient, atLeastOnce).sendMessage(sendMessageRequestCaptor.capture)
      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.values
      capturedRequests should have size 2
      verifyUsagePlanUpdate(
        (capturedRequests.head.messageBody, "BRONZE", ADD),
        (capturedRequests(1).messageBody, "SILVER", ADD)
      )
    }

    "only add the API to high priority usage plans when it's a high priority API" in new Setup {
      when(mockSqsClient.sendMessage(any[SendMessageRequest])).thenReturn(SendMessageResponse.builder().build())
      when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenReturn(builder.build())

      usagePlanService.addApiToUsagePlans(apiId, highPriorityApiNameWithoutVersion)

      val sendMessageRequestCaptor = ArgCaptor[SendMessageRequest]
      verify(mockSqsClient, atLeastOnce).sendMessage(sendMessageRequestCaptor.capture)
      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.values
      capturedRequests should have size 2
      verifyUsagePlanUpdate(
        (capturedRequests.head.messageBody, "BRONZE_HIGH", ADD),
        (capturedRequests(1).messageBody, "SILVER_HIGH", ADD)
      )
    }

    "not add the API to usage plans that already contain the API" in new Setup {
      when(mockSqsClient.sendMessage(any[SendMessageRequest])).thenReturn(SendMessageResponse.builder().build())
      when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenAnswer(getUsagePlanAnswer(Seq(usagePlans("SILVER"))))

      usagePlanService.addApiToUsagePlans(apiId, apiNameWithoutVersion)

      val sendMessageRequestCaptor = ArgCaptor[SendMessageRequest]
      verify(mockSqsClient).sendMessage(sendMessageRequestCaptor.capture)
      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.values
      capturedRequests should have size 1
      verifyUsagePlanUpdate((capturedRequests.head.messageBody, "BRONZE", ADD))
    }

    "not add the API to usage plans that already contain the high priority API" in new Setup {
      when(mockSqsClient.sendMessage(any[SendMessageRequest])).thenReturn(SendMessageResponse.builder().build())
      when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenAnswer(getUsagePlanAnswer(Seq(usagePlans("SILVER_HIGH"))))

      usagePlanService.addApiToUsagePlans(apiId, highPriorityApiNameWithoutVersion)

      val sendMessageRequestCaptor = ArgCaptor[SendMessageRequest]
      verify(mockSqsClient).sendMessage(sendMessageRequestCaptor.capture)
      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.values
      capturedRequests should have size 1
      verifyUsagePlanUpdate((capturedRequests.head.messageBody, "BRONZE_HIGH", ADD))
    }

    "remove the API from usage plans of a different priority" in new Setup {
      when(mockSqsClient.sendMessage(any[SendMessageRequest])).thenReturn(SendMessageResponse.builder().build())
      when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenAnswer(getUsagePlanAnswer(Seq(usagePlans("BRONZE_HIGH"), usagePlans("SILVER_HIGH"))))

      usagePlanService.addApiToUsagePlans(apiId, apiNameWithoutVersion)

      val sendMessageRequestCaptor = ArgCaptor[SendMessageRequest]
      verify(mockSqsClient, atLeastOnce).sendMessage(sendMessageRequestCaptor.capture)
      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.values
      capturedRequests should have size 4
      verifyUsagePlanUpdate(
        (capturedRequests.head.messageBody, "BRONZE_HIGH", REMOVE),
        (capturedRequests(1).messageBody, "SILVER_HIGH", REMOVE),
        (capturedRequests(2).messageBody, "BRONZE", ADD),
        (capturedRequests(3).messageBody, "SILVER", ADD)
      )
    }
  }
}
