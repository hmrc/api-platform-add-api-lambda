package uk.gov.hmrc.apiplatform.addapi

import com.amazonaws.services.lambda.runtime.LambdaLogger
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.Op.{ADD, REMOVE}
import software.amazon.awssdk.services.apigateway.model.Op
import software.amazon.awssdk.services.apigateway.model._
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{SendMessageRequest, SendMessageResponse}
import uk.gov.hmrc.api_platform_manage_api.AwsApiGatewayClient.awsApiGatewayClient
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.JsonMapper

import scala.jdk.CollectionConverters._
import scala.language.postfixOps

class UsagePlanService(apiGatewayClient: ApiGatewayClient,
                       sqsClient: SqsClient,
                       environment: Map[String, String]) extends JsonMapper {

  def this() {
    this(awsApiGatewayClient, SqsClient.create(), sys.env)
  }

  def addApiToUsagePlans(restApiId: String, apiNameWithoutVersion: String)(implicit logger: LambdaLogger): Unit = {
    val baseUsagePlans: Seq[String] = fromJson[Seq[String]](environment("base_usage_plans"))
    val usagePlanIds: Map[String, String] = fromJson[Map[String, String]](environment("usage_plans"))
    val apiPrioritySuffix: String = fromJson[Map[String, String]](environment("api_priority")).get(apiNameWithoutVersion).map("_" + _).getOrElse("")
    val selectedUsagePlans: Seq[String] = baseUsagePlans.map(base => s"$base$apiPrioritySuffix")
    val partitions = usagePlanIds.partition(entry => selectedUsagePlans.contains(entry._1))
    val selectedUserPlansIds: Seq[String] = partitions._1.values.toSeq
    val otherUserPlansIds: Seq[String] = partitions._2.values.toSeq

    otherUserPlansIds foreach { usagePlanId =>
      if (findExistingSubscriptions(usagePlanId).contains(restApiId)) {
        logger.log(s"API $restApiId present in usage plan $usagePlanId. Removing it.")
        sendUpdateMessage(usagePlanId, REMOVE, restApiId)
      } else {
        logger.log(s"API $restApiId not present in usage plan $usagePlanId")
      }
    }

    selectedUserPlansIds foreach { usagePlanId =>
      if (findExistingSubscriptions(usagePlanId).contains(restApiId)) {
        logger.log(s"API $restApiId already present in usage plan $usagePlanId")
      } else {
        logger.log(s"Adding API $restApiId to usage plan $usagePlanId")
        sendUpdateMessage(usagePlanId, ADD, restApiId)
      }
    }
  }

  private def findExistingSubscriptions(usagePlanId: String): Seq[String] = {
    apiGatewayClient
      .getUsagePlan(GetUsagePlanRequest.builder().usagePlanId(usagePlanId).build())
      .apiStages().asScala.toSeq
      .map(_.apiId)
  }

  private def sendUpdateMessage(usagePlanId: String, operation: Op, restApiId: String): SendMessageResponse = {
    val usagePlanUpdateMsg = UsagePlanUpdateMsg(
      usagePlanId,
      Seq(PatchOp(operation.toString, "/apiStages", s"$restApiId:current"))
    )
    sqsClient.sendMessage(
      SendMessageRequest
        .builder()
        .queueUrl(environment("update_usage_plan_queue"))
        .messageBody(toJson(usagePlanUpdateMsg))
        .build()
    )
  }
}

case class UsagePlanUpdateMsg(usagePlanId: String, patchOperations: Seq[PatchOp])
case class PatchOp(op: String, path: String, value: String)
