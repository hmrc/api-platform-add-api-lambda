package uk.gov.hmrc.apiplatform.addapi

import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.Op.REPLACE
import software.amazon.awssdk.services.apigateway.model.{CreateDeploymentRequest, PatchOperation, UpdateStageRequest}

class DeploymentService(apiGatewayClient: ApiGatewayClient) {

  def deployApi(restApiId: String): Unit = {
    apiGatewayClient.createDeployment(buildCreateDeploymentRequest(restApiId))
    apiGatewayClient.updateStage(buildUpdateStageRequest(restApiId))
  }

  private def buildCreateDeploymentRequest(restApiId: String): CreateDeploymentRequest = {
    CreateDeploymentRequest
      .builder()
      .restApiId(restApiId)
      .stageName("current")
      .build()
  }

  private def buildUpdateStageRequest(restApiId: String): UpdateStageRequest = {
    UpdateStageRequest
      .builder()
      .restApiId(restApiId)
      .stageName("current")
      .patchOperations(PatchOperation.builder().op(REPLACE).path("/*/*/logging/loglevel").value("INFO").build())
      .build()
  }
}
