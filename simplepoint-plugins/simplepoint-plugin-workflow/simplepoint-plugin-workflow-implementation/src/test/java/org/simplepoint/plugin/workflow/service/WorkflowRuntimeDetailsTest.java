package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowProgress.IdentityLabel;

class WorkflowRuntimeDetailsTest extends WorkflowDirectoryFixture {
  @Test
  void aPriorEngineCanceledExecutionIsNotRewrittenAsALaterWithdrawal() {
    String definition = publish("${initiator}", null);
    var instance = transaction.execute(status -> service.start(definition, null));
    String originalTask =
        engine
            .getTaskService()
            .createTaskQuery()
            .processInstanceId(instance.id())
            .singleResult()
            .getId();
    engine
        .getRuntimeService()
        .createChangeActivityStateBuilder()
        .processInstanceId(instance.id())
        .moveActivityIdTo("review", "review")
        .changeState();
    transaction.execute(status -> service.withdraw(instance.id(), "Later withdrawal"));
    var original =
        engine
            .getHistoryService()
            .createHistoricTaskInstanceQuery()
            .taskId(originalTask)
            .singleResult();
    assertThat(original.getDeleteReason()).isNotBlank().isNotEqualTo("simplepoint:WITHDRAWN");
    var nodes = transaction.execute(status -> service.nodeHistory(instance.id(), "review", 0, 20));
    assertThat(nodes.content())
        .filteredOn(node -> originalTask.equals(node.taskId()))
        .singleElement()
        .satisfies(
            node -> {
              assertThat(node.status()).isEqualTo("CANCELED");
              assertThat(node.outcome()).isEqualTo("CANCELED");
              assertThat(node.comment()).isNull();
              assertThat(node.actorName()).isNull();
            });
    var diagram = transaction.execute(status -> service.diagram(instance.id()));
    assertThat(diagram.activities())
        .filteredOn(node -> originalTask.equals(node.taskId()))
        .singleElement()
        .satisfies(node -> assertThat(node.status()).isEqualTo("CANCELED"));
  }

  @Test
  void repeatExecutionsAreDistinctPagedRecordsAndCurrentApprovalIsQueriedSeparately() {
    var instance =
        transaction.execute(
            status ->
                service.start(
                    publishLoop(),
                    new StartProcessCommand(null, null, "Loop", Map.of("repeat", true), null)));
    for (String comment : List.of("第一次通过", "第二次通过")) {
      String task =
          engine
              .getTaskService()
              .createTaskQuery()
              .processInstanceId(instance.id())
              .singleResult()
              .getId();
      transaction.execute(
          status -> service.complete(task, new CompleteTaskCommand(Map.of(), comment)));
    }
    var current =
        transaction.execute(status -> service.nodeHistory(instance.id(), "review", 0, 20, true));
    assertThat(current.totalElements()).isEqualTo(1);
    assertThat(current.content())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.status()).isEqualTo("ACTIVE");
              assertThat(row.outcome()).isEqualTo("PENDING");
              assertThat(row.actorName()).isNull();
            });
    var latest = transaction.execute(status -> service.nodeHistory(instance.id(), "review", 1, 1));
    var first = transaction.execute(status -> service.nodeHistory(instance.id(), "review", 2, 1));
    assertThat(latest.totalElements()).isEqualTo(3);
    assertThat(latest.content())
        .singleElement()
        .satisfies(row -> assertThat(row.comment()).isEqualTo("第二次通过"));
    assertThat(first.content())
        .singleElement()
        .satisfies(row -> assertThat(row.comment()).isEqualTo("第一次通过"));
    assertThat(latest.content().getFirst().id()).isNotEqualTo(first.content().getFirst().id());
  }

  @Test
  void longRunningLoopsHaveABoundedCanvasAndCompletePagedNodeHistory() {
    var instance =
        transaction.execute(
            status ->
                service.start(
                    publishLoop(),
                    new StartProcessCommand(
                        null, null, "Long loop", Map.of("repeat", true), null)));
    for (int index = 0; index < 130; index++) {
      String task =
          engine
              .getTaskService()
              .createTaskQuery()
              .processInstanceId(instance.id())
              .singleResult()
              .getId();
      engine.getTaskService().complete(task, "actor");
    }
    var diagram = transaction.execute(status -> service.diagram(instance.id()));
    assertThat(diagram.historyTruncated()).isTrue();
    assertThat(diagram.totalActivities()).isGreaterThan(500);
    assertThat(diagram.activities()).hasSize(500);
    assertThat(diagram.activeActivityIds()).contains("review");
    var lastPage =
        transaction.execute(status -> service.nodeHistory(instance.id(), "review", 6, 20));
    assertThat(lastPage.totalElements()).isEqualTo(131);
    assertThat(lastPage.content()).hasSize(11);
  }

  @Test
  void currentCandidatesAndCompletedDecisionHaveRealNamesWithoutCreditingCandidatesAsActors() {
    String definition = publishRole("tenant.approver");
    var instance =
        transaction.execute(
            status ->
                service.start(
                    definition,
                    new StartProcessCommand(null, null, "Node detail", Map.of(), null)));
    String task =
        engine
            .getTaskService()
            .createTaskQuery()
            .processInstanceId(instance.id())
            .singleResult()
            .getId();
    engine.getTaskService().addCandidateUser(task, "actor");
    var pending =
        transaction.execute(status -> service.nodeHistory(instance.id(), "review", 0, 20));
    assertThat(pending.content())
        .singleElement()
        .satisfies(
            node -> {
              assertThat(node.id()).isNotBlank();
              assertThat(node.taskId()).isEqualTo(task);
              assertThat(node.status()).isEqualTo("ACTIVE");
              assertThat(node.actorName()).isNull();
              assertThat(node.candidateUsers()).containsExactly(new IdentityLabel("张三"));
              assertThat(node.candidateRoles()).containsExactly(new IdentityLabel("审批人员"));
            });
    transaction.execute(
        status -> service.complete(task, new CompleteTaskCommand(Map.of(), "核对无误")));
    var completed =
        transaction.execute(status -> service.nodeHistory(instance.id(), "review", 0, 20));
    assertThat(completed.content())
        .singleElement()
        .satisfies(
            node -> {
              assertThat(node.actorName()).isEqualTo("张三");
              assertThat(node.assigneeName()).isEqualTo("张三");
              assertThat(node.outcome()).isEqualTo("APPROVED");
              assertThat(node.comment()).isEqualTo("核对无误");
              assertThat(node.endedAt()).isNotNull();
              assertThat(node.candidateUsers()).isEmpty();
            });
    var progress = transaction.execute(status -> service.progress(instance.id()));
    assertThat(progress.matter().starterName()).isEqualTo("张三");
    assertThat(progress.matter().status()).isEqualTo("COMPLETED");
  }

  @Test
  void historicalRejectionShowsActualOutcomeAndReasonAndNonexistentNodeIsEmpty() {
    String definition = publish("${initiator}", null);
    var instance = transaction.execute(status -> service.start(definition, null));
    String task =
        engine
            .getTaskService()
            .createTaskQuery()
            .processInstanceId(instance.id())
            .singleResult()
            .getId();
    transaction.execute(status -> service.reject(task, "资料缺失"));
    var rejected =
        transaction.execute(status -> service.nodeHistory(instance.id(), "review", 0, 20));
    assertThat(rejected.content())
        .singleElement()
        .satisfies(
            node -> {
              assertThat(node.status()).isEqualTo("REJECTED");
              assertThat(node.outcome()).isEqualTo("REJECTED");
              assertThat(node.actorName()).isEqualTo("张三");
              assertThat(node.comment()).isEqualTo("资料缺失");
            });
    var empty =
        transaction.execute(status -> service.nodeHistory(instance.id(), "not-a-node", 0, 20));
    assertThat(empty.totalElements()).isZero();
  }

  @Test
  void authorizationAndBoundedPagingApplyBeforeNodeOrIdentityHistoryCanBeRead() {
    String definition = publish("${initiator}", null);
    var instance = transaction.execute(status -> service.start(definition, null));
    authorize("reviewer", "tenant", false, List.of("workflow.tasks.view"));
    assertThatThrownBy(
            () ->
                transaction.execute(status -> service.nodeHistory(instance.id(), "review", 0, 20)))
        .isInstanceOf(WorkflowException.class);
    assertThatThrownBy(() -> transaction.execute(status -> service.progress(instance.id())))
        .isInstanceOf(WorkflowException.class);
    authorize("actor", "other-tenant", true, List.of());
    assertThatThrownBy(
            () ->
                transaction.execute(status -> service.nodeHistory(instance.id(), "review", 0, 20)))
        .isInstanceOf(WorkflowException.class);
    authorize("actor", "tenant", false, List.of());
    assertThatThrownBy(
            () ->
                transaction.execute(status -> service.nodeHistory(instance.id(), "review", 0, 20)))
        .isInstanceOf(WorkflowException.class);
    authorize("actor", "tenant", true, List.of());
    assertThatThrownBy(
            () ->
                transaction.execute(status -> service.nodeHistory(instance.id(), "review", 0, 51)))
        .isInstanceOf(WorkflowException.class);
  }

  private String publishLoop() {
    var initial =
        service.createDefinition(
            new CreateDefinitionCommand("loopApproval", "Loop approval", null, null));
    String xml =
        initial
            .bpmnXml()
            .replace(
                "<bpmn:sequenceFlow id=\"flow_review_end\" sourceRef=\"review\""
                    + " targetRef=\"end\"/>",
                """
<bpmn:sequenceFlow id="flow_review_end" sourceRef="review" targetRef="decision"/>
<bpmn:exclusiveGateway id="decision" default="finish"/>
<bpmn:sequenceFlow id="repeat" sourceRef="decision" targetRef="review">
  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${repeat}</bpmn:conditionExpression>
</bpmn:sequenceFlow>
<bpmn:sequenceFlow id="finish" sourceRef="decision" targetRef="end"/>
""");
    service.updateDraft(
        initial.definition().id(),
        new UpdateDraftCommand(1, "Loop approval", null, null, xml, null));
    service.publish(initial.definition().id(), new PublishDefinitionCommand(2, null));
    return initial.definition().id();
  }
}
