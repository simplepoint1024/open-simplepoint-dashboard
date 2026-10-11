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
import org.simplepoint.plugin.workflow.entity.WorkflowActionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowNotificationOutboxEntity;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/** Real shared JPA/Flowable transaction: assignments, outcomes and progress stay consistent. */
class WorkflowAssignmentTransactionTest extends WorkflowEngineFixture {

  @Test
  void cancellationRecordsItsOperatorAndReasonInsteadOfPretendingThatTheTaskWasApproved() {
    String definition = publish("${initiator}", null);
    var instance =
        transaction.execute(
            status ->
                service.start(
                    definition,
                    new StartProcessCommand(1, null, "Canceled matter", Map.of(), List.of())));
    transaction.execute(status -> service.cancel(instance.id(), "No longer needed"));

    var actions =
        transaction.execute(
            status ->
                SharedEntityManagerCreator.createSharedEntityManager(factory)
                    .createQuery(
                        "select a from WorkflowActionEntity a where a.actionType = 'CANCELED'",
                        WorkflowActionEntity.class)
                    .getResultList());
    assertThat(actions)
        .singleElement()
        .satisfies(
            action -> {
              assertThat(action.getProcessInstanceId()).isEqualTo(instance.id());
              assertThat(action.getActorId()).isEqualTo("actor");
              assertThat(action.getComment()).isEqualTo("No longer needed");
              assertThat(action.getTaskId()).isNull();
            });
    Long approvals =
        transaction.execute(
            status ->
                SharedEntityManagerCreator.createSharedEntityManager(factory)
                    .createQuery(
                        "select count(a) from WorkflowActionEntity a where a.actionType ="
                            + " 'APPROVED'",
                        Long.class)
                    .getSingleResult());
    assertThat(approvals).isZero();
  }

  @Test
  void approvalRecordsOneTaskDecisionAndItsActualCommentInTheEngineTransaction() {
    String definition = publish("${initiator}", null);
    var instance =
        transaction.execute(
            status ->
                service.start(
                    definition,
                    new StartProcessCommand(1, null, "Approved matter", Map.of(), List.of())));
    var task =
        engine.getTaskService().createTaskQuery().processInstanceId(instance.id()).singleResult();
    transaction.execute(
        status ->
            service.complete(
                task.getId(), new CompleteTaskCommand(Map.of(), "Approved with review")));

    var actions =
        transaction.execute(
            status ->
                SharedEntityManagerCreator.createSharedEntityManager(factory)
                    .createQuery(
                        "select a from WorkflowActionEntity a where a.actionType = 'APPROVED'",
                        WorkflowActionEntity.class)
                    .getResultList());
    assertThat(actions)
        .singleElement()
        .satisfies(
            action -> {
              assertThat(action.getActorId()).isEqualTo("actor");
              assertThat(action.getTaskId()).isEqualTo(task.getId());
              assertThat(action.getDecisionTaskId()).isEqualTo(task.getId());
              assertThat(action.getTaskDefinitionKey()).isEqualTo("review");
              assertThat(action.getComment()).isEqualTo("Approved with review");
            });
    assertThat(
            engine
                .getHistoryService()
                .createHistoricTaskInstanceQuery()
                .taskId(task.getId())
                .singleResult()
                .getCompletedBy())
        .isEqualTo("actor");
    assertThat(engine.getTaskService().getTaskComments(task.getId()))
        .singleElement()
        .satisfies(comment -> assertThat(comment.getUserId()).isEqualTo("actor"));
  }

  @Test
  void canceledApprovalIsNotReportedAsCompletedInTheProgressDiagram() {
    String definition = publish("${initiator}", null);
    var instance =
        transaction.execute(
            status ->
                service.start(
                    definition,
                    new StartProcessCommand(1, null, "Canceled matter", Map.of(), List.of())));
    transaction.execute(status -> service.cancel(instance.id(), "Request canceled"));

    var diagram = transaction.execute(status -> service.diagram(instance.id()));
    assertThat(diagram.instance().status()).isEqualTo("CANCELED");
    assertThat(diagram.activeActivityIds()).isEmpty();
    assertThat(diagram.activities())
        .filteredOn(activity -> "review".equals(activity.activityId()))
        .singleElement()
        .satisfies(activity -> assertThat(activity.status()).isEqualTo("CANCELED"));
    assertThat(transaction.execute(status -> service.completedTasks(null, 0, 20)).content())
        .hasSize(1);
  }

  @Test
  void returningToTheSameNodeDoesNotMarkItsPreviousCompletedOccurrenceAsActive() {
    String definition =
        transaction.execute(
            status -> {
              var initial =
                  service.createDefinition(
                      new CreateDefinitionCommand("repeatApproval", "Repeat approval", null, null));
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
                  new UpdateDraftCommand(1, "Repeat approval", null, null, xml, null));
              service.publish(initial.definition().id(), new PublishDefinitionCommand(2, null));
              return initial.definition().id();
            });
    var instance =
        transaction.execute(
            status ->
                service.start(
                    definition,
                    new StartProcessCommand(
                        1, null, "Repeat matter", Map.of("repeat", true), List.of())));
    var task =
        engine.getTaskService().createTaskQuery().processInstanceId(instance.id()).singleResult();
    transaction.execute(
        status -> service.complete(task.getId(), new CompleteTaskCommand(Map.of(), "First pass")));

    var diagram = transaction.execute(status -> service.diagram(instance.id()));
    assertThat(diagram.activities())
        .filteredOn(activity -> "review".equals(activity.activityId()))
        .extracting(activity -> activity.status())
        .containsExactly("COMPLETED", "ACTIVE");
    assertThat(diagram.activities())
        .filteredOn(activity -> "review".equals(activity.activityId()))
        .extracting(activity -> activity.id())
        .doesNotHaveDuplicates()
        .doesNotContainNull();
    assertThat(diagram.activities())
        .filteredOn(activity -> "review".equals(activity.activityId()))
        .extracting(activity -> activity.taskId())
        .doesNotHaveDuplicates()
        .doesNotContainNull();
  }

  @Test
  void aForeignAssigneeResolvedFromLegacyAdvancedVariablesRollsBackEngineInstanceAndOutbox() {
    String id = publish("${approver}", null);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        service.start(
                            id,
                            new StartProcessCommand(
                                1,
                                null,
                                "Private matter",
                                Map.of("approver", "foreign-user"),
                                List.of()))))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("有效成员");
    assertNoRuntimeOrOutbox();
  }

  @Test
  void aForeignCandidateUserRollsBackInTheSameTransaction() {
    String id = publish(null, "${reviewers}");
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        service.start(
                            id,
                            new StartProcessCommand(
                                1,
                                null,
                                "Private matter",
                                Map.of("reviewers", List.of("foreign-user")),
                                List.of()))))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("有效成员");
    assertNoRuntimeOrOutbox();
  }

  @Test
  void validTenantAssigneesCommitTheEngineInstancePlatformProjectionAndNotificationTogether() {
    String id = publish("${approver}", null);
    var started =
        transaction.execute(
            status ->
                service.start(
                    id,
                    new StartProcessCommand(
                        1, null, "Allowed matter", Map.of("approver", "actor"), List.of())));
    assertThat(started).isNotNull();
    assertThat(engine.getTaskService().createTaskQuery().taskAssignee("actor").count())
        .isEqualTo(1);
    assertThat(count(WorkflowInstanceEntity.class)).isEqualTo(1);
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isEqualTo(1);
  }

  @Test
  void foreignOrDeletedDynamicCandidateRolesRollBackInsteadOfCreatingAnUnclaimableTask() {
    String id = publishRole("${reviewRoles}");
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        service.start(
                            id,
                            new StartProcessCommand(
                                1,
                                null,
                                "Private matter",
                                Map.of("reviewRoles", List.of("foreign.approver")),
                                List.of()))))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("角色");
    assertNoRuntimeOrOutbox();
  }

  @Test
  void existingTenantRoleAuthoritiesAreValidCandidateGroups() {
    String id = publishRole("${reviewRoles}");
    transaction.execute(
        status ->
            service.start(
                id,
                new StartProcessCommand(
                    1,
                    null,
                    "Allowed matter",
                    Map.of("reviewRoles", List.of("tenant.approver")),
                    List.of())));
    assertThat(
            engine.getTaskService().createTaskQuery().taskCandidateGroup("tenant.approver").count())
        .isEqualTo(1);
    assertThat(count(WorkflowInstanceEntity.class)).isEqualTo(1);
  }

  @Test
  void fixedNonAsciiAuthoritiesRemainRealFlowableCandidateGroups() {
    String id = publishRole("部门/审核");
    transaction.execute(
        status ->
            service.start(
                id, new StartProcessCommand(1, null, "Allowed matter", Map.of(), List.of())));
    assertThat(engine.getTaskService().createTaskQuery().taskCandidateGroup("部门/审核").count())
        .isEqualTo(1);
    assertThat(count(WorkflowInstanceEntity.class)).isEqualTo(1);
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isZero();
    assertThat(count(WorkflowActionEntity.class)).isEqualTo(1);
  }

  private void assertNoRuntimeOrOutbox() {
    assertThat(engine.getRuntimeService().createProcessInstanceQuery().count()).isZero();
    assertThat(engine.getTaskService().createTaskQuery().count()).isZero();
    assertThat(count(WorkflowInstanceEntity.class)).isZero();
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isZero();
    assertThat(count(WorkflowActionEntity.class)).isZero();
  }
}
