package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowInboxItem;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Real engine + JPA queries: tests the pinned schema, SQL pagination and identity boundaries. */
class WorkflowInboxQueriesTest extends WorkflowDirectoryFixture {
  private WorkflowInboxQueries inbox;

  @BeforeEach
  void inboxQueries() {
    inbox = new WorkflowInboxQueries(em);
  }

  @Test
  void directTaskLinksUseTheSamePersonalBoundaryAsTheInboxEvenOutsideTheCurrentPage() {
    var instance = start(publish("${initiator}", null), "Direct task");
    String taskId = task(instance);
    var item = transaction.execute(status -> inbox.task(taskId));
    assertThat(item.taskId()).isEqualTo(taskId);
    assertThat(item.starterName()).isEqualTo("张三");
    authorize("reviewer", "tenant", true, List.of());
    assertThatThrownBy(() -> transaction.execute(status -> inbox.task(taskId)))
        .isInstanceOf(WorkflowException.class);
    authorize("actor", "other-tenant", true, List.of());
    assertThatThrownBy(() -> transaction.execute(status -> inbox.task(taskId)))
        .isInstanceOf(WorkflowException.class);
  }

  @Test
  void pendingUsesRealNamesAndIsRemovedAfterApprovalWithActualDecisionAndComment() {
    var instance = start(publish("${initiator}", null), "Purchase");
    var pending = rows("pending");
    assertThat(pending.totalElements()).isEqualTo(1);
    assertThat(pending.content())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.starterName()).isEqualTo("张三");
              assertThat(item.assigneeName()).isEqualTo("张三");
              assertThat(item.actorName()).isNull();
              assertThat(item.actionable()).isTrue();
              assertThat(item.claimable()).isFalse();
              assertThat(item.outcome()).isEqualTo("PENDING");
            });
    transaction.execute(
        status -> service.complete(task(instance), new CompleteTaskCommand(Map.of(), "同意")));
    assertThat(rows("pending").content()).isEmpty();
    assertThat(rows("completed").content())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.outcome()).isEqualTo("APPROVED");
              assertThat(item.instanceStatus()).isEqualTo("COMPLETED");
              assertThat(item.actorName()).isEqualTo("张三");
              assertThat(item.comment()).isEqualTo("同意");
              assertThat(item.handledByMe()).isTrue();
              assertThat(item.actionable()).isFalse();
            });
  }

  @Test
  void withdrawalWithoutAssignedTaskIsAProcessActionAndAssigneeSeesWhoTerminatedTheirTask() {
    var instance = start(publish("${initiator}", null), "Withdraw");
    engine.getTaskService().setAssignee(task(instance), "reviewer");
    transaction.execute(status -> service.withdraw(instance.id(), "资料需要修改"));
    assertThat(rows("completed").content())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.taskId()).isNull();
              assertThat(item.rowType()).isEqualTo("PROCESS_ACTION");
              assertThat(item.outcome()).isEqualTo("WITHDRAWN");
              assertThat(item.handledByMe()).isTrue();
            });
    authorize("reviewer", "tenant", false, List.of("workflow.tasks.view"));
    assertThat(rows("completed").content())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.taskId()).isNotNull();
              assertThat(item.outcome()).isEqualTo("WITHDRAWN");
              assertThat(item.actorName()).isEqualTo("张三");
              assertThat(item.handledByMe()).isFalse();
            });
  }

  @Test
  void cancelingAnOwnTaskDoesNotCreateADuplicateProcessAction() {
    var instance = start(publish("${initiator}", null), "Cancel");
    transaction.execute(status -> service.cancel(instance.id(), "作废"));
    assertThat(rows("completed").content())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.rowType()).isEqualTo("TASK");
              assertThat(item.outcome()).isEqualTo("CANCELED");
              assertThat(item.comment()).isEqualTo("作废");
            });
  }

  @Test
  void rejectionIsAFirstClassCompletedResult() {
    var instance = start(publish(null, "actor"), "Reject");
    transaction.execute(status -> service.reject(task(instance), "不符合要求"));
    assertThat(rows("completed").content())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.outcome()).isEqualTo("REJECTED");
              assertThat(item.instanceStatus()).isEqualTo("REJECTED");
              assertThat(item.actorName()).isEqualTo("张三");
              assertThat(item.handledByMe()).isTrue();
            });
  }

  @Test
  void anEarlierApprovalIsNotRewrittenBySubsequentCancellation() {
    String definition =
        transaction.execute(
            status -> {
              var initial =
                  service.createDefinition(
                      new CreateDefinitionCommand("twoSteps", "Two steps", null, null));
              String xml =
                  initial
                      .bpmnXml()
                      .replace(
                          "<bpmn:sequenceFlow id=\"flow_review_end\" sourceRef=\"review\""
                              + " targetRef=\"end\"/>",
                          """
<bpmn:sequenceFlow id="flow_review_end" sourceRef="review" targetRef="next"/>
<bpmn:userTask id="next" name="Next review" flowable:assignee="${initiator}"/>
<bpmn:sequenceFlow id="finish" sourceRef="next" targetRef="end"/>
""");
              service.updateDraft(
                  initial.definition().id(),
                  new UpdateDraftCommand(1, "Two steps", null, null, xml, null));
              service.publish(initial.definition().id(), new PublishDefinitionCommand(2, null));
              return initial.definition().id();
            });
    var instance = start(definition, "Sequential");
    transaction.execute(
        status ->
            service.complete(task(instance), new CompleteTaskCommand(Map.of(), "First accepted")));
    transaction.execute(status -> service.cancel(instance.id(), "Stop next step"));
    var completed = rows("completed");
    assertThat(completed.content())
        .extracting(WorkflowInboxItem::outcome)
        .containsExactly("CANCELED", "APPROVED");
    assertThat(completed.content())
        .extracting(WorkflowInboxItem::instanceStatus)
        .containsOnly("CANCELED");
    assertThat(completed.content())
        .filteredOn(item -> item.outcome().equals("APPROVED"))
        .singleElement()
        .extracting(WorkflowInboxItem::comment)
        .isEqualTo("First accepted");
  }

  @Test
  void candidatesAreDeduplicatedAndNeverReceiveOtherPeoplesCompletedWork() {
    var instance = start(publish(null, "actor"), "Candidates");
    engine.getTaskService().addCandidateGroup(task(instance), "tenant.approver");
    AuthorizationContextHolder.getContext().setRoles(List.of("tenant.approver"));
    assertThat(rows("pending").totalElements()).isEqualTo(1);
    assertThat(rows("pending").content())
        .singleElement()
        .extracting(WorkflowInboxItem::claimable)
        .isEqualTo(true);
    engine.getTaskService().setAssignee(task(instance), "reviewer");
    assertThat(rows("pending").content()).isEmpty();
    engine.getTaskService().complete(task(instance), "reviewer");
    assertThat(rows("completed").content()).isEmpty();
  }

  @Test
  void roleCandidatesCanSeePendingButNotAnUnclaimedTerminatedTask() {
    var instance = start(publishRole("tenant.approver"), "Role");
    authorize("reviewer", "tenant", false, List.of("workflow.tasks.view"));
    AuthorizationContextHolder.getContext().setRoles(List.of("tenant.approver"));
    assertThat(rows("pending").totalElements()).isEqualTo(1);
    assertThat(rows("pending").content())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.actionable()).isFalse();
              assertThat(item.claimable()).isFalse();
            });
    authorize("actor", "tenant", true, List.of());
    transaction.execute(status -> service.withdraw(instance.id(), "No decision yet"));
    authorize("reviewer", "tenant", false, List.of("workflow.tasks.view"));
    AuthorizationContextHolder.getContext().setRoles(List.of("tenant.approver"));
    assertThat(rows("completed").content()).isEmpty();
  }

  @Test
  void namesAndFiltersAndCountsUseTheWholeDatasetBeforePaging() {
    String definition = publish("${initiator}", null);
    for (String title : List.of("Zulu", "Beta", "Alpha", "Bravo")) {
      start(definition, title);
    }
    var page =
        transaction.execute(
            status ->
                inbox.query(
                    "pending",
                    Map.of("title", "like:b", "starterName", "equals:张三"),
                    PageRequest.of(1, 1, Sort.by("title"))));
    assertThat(page.totalElements()).isEqualTo(2);
    assertThat(page.content())
        .singleElement()
        .extracting(WorkflowInboxItem::title)
        .isEqualTo("Bravo");
    var nameMiss =
        transaction.execute(
            status ->
                inbox.query(
                    "pending", Map.of("starterName", "equals:不是实名"), PageRequest.of(0, 20)));
    assertThat(nameMiss.totalElements()).isZero();
  }

  @Test
  void identityChangesAffectBothDisplayAndFilteringWithoutExposingAnIdFallback() {
    start(publish("${initiator}", null), "Identity");
    transaction.executeWithoutResult(
        status ->
            em.createNativeQuery("update simpoint_ac_users set name = '真实姓名' where id = 'actor'")
                .executeUpdate());
    assertThat(rows("pending").content())
        .singleElement()
        .extracting(WorkflowInboxItem::starterName)
        .isEqualTo("真实姓名");
    transaction.executeWithoutResult(
        status ->
            em.createNativeQuery(
                    "update simpoint_ac_users set deleted_at = current_timestamp where id ="
                        + " 'actor'")
                .executeUpdate());
    assertThat(rows("pending").content())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.starterName()).isNull();
              assertThat(item.assigneeName()).isNull();
            });
  }

  @Test
  void legacyEngineCompletionIsNotInventedAsAnApprovalAndEndedProcessIsNotShownRunning() {
    var instance = start(publish("${initiator}", null), "Legacy");
    engine.getTaskService().complete(task(instance));
    assertThat(rows("completed").content())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.outcome()).isEqualTo("COMPLETED");
              assertThat(item.instanceStatus()).isEqualTo("COMPLETED");
              assertThat(item.actorName()).isNull();
              assertThat(item.handledByMe()).isFalse();
            });
  }

  @Test
  void queryAndSchemaEnforceScopePermissionsAndExplicitFieldBudget() {
    start(publish("${initiator}", null), "Hidden");
    authorize("actor", "another-tenant", true, List.of());
    assertThat(rows("pending").content()).isEmpty();
    authorize("reviewer", "tenant", true, List.of());
    assertThat(rows("pending").content()).isEmpty();
    authorize("actor", "tenant", false, List.of());
    assertThatThrownBy(() -> rows("pending")).isInstanceOf(WorkflowException.class);
    assertThatThrownBy(inbox::schema).isInstanceOf(WorkflowException.class);
    authorize("actor", "tenant", false, List.of("workflow.tasks.view"));
    for (var filters :
        List.of(
            Map.of("tenantId", "equals:another-tenant"), Map.of("assigneeId", "equals:actor"))) {
      assertThatThrownBy(
              () ->
                  transaction.execute(
                      status -> inbox.query("pending", filters, PageRequest.of(0, 20))))
          .isInstanceOf(InvalidQueryException.class);
    }
    assertThatThrownBy(() -> inbox.query("pending", Map.of(), PageRequest.of(0, 101)))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(
            () -> inbox.query("pending", Map.of(), PageRequest.of(0, 20, Sort.by("actorId"))))
        .isInstanceOf(InvalidQueryException.class);
    assertThat(inbox.schema().toString())
        .contains("starterName", "x-query", "handledAt")
        .doesNotContain("assigneeId", "tenantId");
  }

  private ProcessInstanceSummary start(String definition, String title) {
    return transaction.execute(
        status ->
            service.start(definition, new StartProcessCommand(null, null, title, Map.of(), null)));
  }

  private String task(ProcessInstanceSummary instance) {
    return engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(instance.id())
        .singleResult()
        .getId();
  }

  private PageResult<WorkflowInboxItem> rows(String tab) {
    return transaction.execute(status -> inbox.query(tab, Map.of(), PageRequest.of(0, 20)));
  }
}
