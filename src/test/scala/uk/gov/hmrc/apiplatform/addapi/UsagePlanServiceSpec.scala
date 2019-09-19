package uk.gov.hmrc.apiplatform.addapi

import java.util.UUID

import com.amazonaws.services.lambda.runtime.LambdaLogger
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.Op.{ADD, REMOVE}
import software.amazon.awssdk.services.apigateway.model._
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{SendMessageRequest, SendMessageResponse}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.JsonMapper

import scala.collection.JavaConverters._

class UsagePlanServiceSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
    implicit val mockLambdaLogger: LambdaLogger = mock[LambdaLogger]
    val apiId: String = UUID.randomUUID().toString
    val apiNameWithoutVersion = "foo"
    val highPriorityApiNameWithoutVersion = "bar"
    val baseUsagePlans: Seq[String] = Seq("BRONZE", "SILVER")
    val apiPriority: Map[String, String] = Map(highPriorityApiNameWithoutVersion -> "HIGH")
    val usagePlans: Map[String, String] = Map("BRONZE" -> "1", "SILVER" -> "2", "BRONZE_HIGH" -> "3", "SILVER_HIGH" -> "4")
    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val mockSqsClient: SqsClient = mock[SqsClient]
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

    class GetUsagePlanAnswer(var usagePlansIds: Seq[String]) extends Answer[GetUsagePlanResponse] {
      override def answer(invocationOnMock: InvocationOnMock): GetUsagePlanResponse = {
        val request: GetUsagePlanRequest = invocationOnMock.getArgument(0)
        if (usagePlansIds.contains(request.usagePlanId)) {
          GetUsagePlanResponse.builder().apiStages(ApiStage.builder().apiId(apiId).stage("current").build()).build()
        } else {
          GetUsagePlanResponse.builder().build()
        }
      }
    }
  }

  "Add API to usage plans" should {
    "only add the API to base usage plans when it's not a high priority API" in new Setup {
      val sendMessageRequestCaptor: ArgumentCaptor[SendMessageRequest] = ArgumentCaptor.forClass(classOf[SendMessageRequest])
      when(mockSqsClient.sendMessage(sendMessageRequestCaptor.capture())).thenReturn(SendMessageResponse.builder().build())
      when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenAnswer(new GetUsagePlanAnswer(Seq.empty))

      usagePlanService.addApiToUsagePlans(apiId, apiNameWithoutVersion)

      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.getAllValues.asScala
      capturedRequests should have size 2
      verifyUsagePlanUpdate(
        (capturedRequests.head.messageBody, "BRONZE", ADD),
        (capturedRequests(1).messageBody, "SILVER", ADD)
      )
    }

    "only add the API to high priority usage plans when it's a high priority API" in new Setup {
      val sendMessageRequestCaptor: ArgumentCaptor[SendMessageRequest] = ArgumentCaptor.forClass(classOf[SendMessageRequest])
      when(mockSqsClient.sendMessage(sendMessageRequestCaptor.capture())).thenReturn(SendMessageResponse.builder().build())
      when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenAnswer(new GetUsagePlanAnswer(Seq.empty))

      usagePlanService.addApiToUsagePlans(apiId, highPriorityApiNameWithoutVersion)

      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.getAllValues.asScala
      capturedRequests should have size 2
      verifyUsagePlanUpdate(
        (capturedRequests.head.messageBody, "BRONZE_HIGH", ADD),
        (capturedRequests(1).messageBody, "SILVER_HIGH", ADD)
      )
    }

    "not add the API to usage plans that already contain the API" in new Setup {
      val sendMessageRequestCaptor: ArgumentCaptor[SendMessageRequest] = ArgumentCaptor.forClass(classOf[SendMessageRequest])
      when(mockSqsClient.sendMessage(sendMessageRequestCaptor.capture())).thenReturn(SendMessageResponse.builder().build())
      when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenAnswer(new GetUsagePlanAnswer(Seq(usagePlans("SILVER"))))

      usagePlanService.addApiToUsagePlans(apiId, apiNameWithoutVersion)

      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.getAllValues.asScala
      capturedRequests should have size 1
      verifyUsagePlanUpdate((capturedRequests.head.messageBody, "BRONZE", ADD))
    }

    "not add the API to usage plans that already contain the high priority API" in new Setup {
      val sendMessageRequestCaptor: ArgumentCaptor[SendMessageRequest] = ArgumentCaptor.forClass(classOf[SendMessageRequest])
      when(mockSqsClient.sendMessage(sendMessageRequestCaptor.capture())).thenReturn(SendMessageResponse.builder().build())
      when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenAnswer(new GetUsagePlanAnswer(Seq(usagePlans("SILVER_HIGH"))))

      usagePlanService.addApiToUsagePlans(apiId, highPriorityApiNameWithoutVersion)

      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.getAllValues.asScala
      capturedRequests should have size 1
      verifyUsagePlanUpdate((capturedRequests.head.messageBody, "BRONZE_HIGH", ADD))
    }

    "remove the API from usage plans of a different priority" in new Setup {
      val sendMessageRequestCaptor: ArgumentCaptor[SendMessageRequest] = ArgumentCaptor.forClass(classOf[SendMessageRequest])
      when(mockSqsClient.sendMessage(sendMessageRequestCaptor.capture())).thenReturn(SendMessageResponse.builder().build())
      when(mockAPIGatewayClient.getUsagePlan(any[GetUsagePlanRequest])).thenAnswer(new GetUsagePlanAnswer(Seq(usagePlans("BRONZE_HIGH"), usagePlans("SILVER_HIGH"))))

      usagePlanService.addApiToUsagePlans(apiId, apiNameWithoutVersion)

      val capturedRequests: Seq[SendMessageRequest] = sendMessageRequestCaptor.getAllValues.asScala
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
