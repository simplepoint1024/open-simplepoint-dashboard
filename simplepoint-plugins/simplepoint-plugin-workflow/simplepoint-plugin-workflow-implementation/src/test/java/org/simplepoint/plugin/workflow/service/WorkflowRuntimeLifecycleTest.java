package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowException.Code;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.entity.WorkflowActionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/** Real engine/persistence checks for decisions, cancellation, access and transaction races. */
class WorkflowRuntimeLifecycleTest extends WorkflowEngineFixture {

  @Test
  void aCandidateCanRejectWithAnExplicitReasonAndBecomesTheRecordedDecisionMaker() {
    var instance = start(publish(null, "actor"));
    String task = taskId(instance);
    authorize("actor", "tenant", false, List.of("workflow.tasks.complete"));
    var rejected =
        transaction.execute(status -> service.reject(task, "  Missing supporting information  "));

    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThat(
            engine
                .getRuntimeService()
                .createProcessInstanceQuery()
                .processInstanceId(instance.id())
                .count())
        .isZero();
    var historicalTask =
        engine.getHistoryService().createHistoricTaskInstanceQuery().taskId(task).singleResult();
    assertThat(historicalTask.getAssignee()).isEqualTo("actor");
    assertThat(historicalTask.getDeleteReason()).isEqualTo("simplepoint:REJECTED");
    assertThat(actions("REJECTED"))
        .singleElement()
        .satisfies(
            action -> {
              assertThat(action.getDecisionTaskId()).isEqualTo(task);
              assertThat(action.getActorId()).isEqualTo("actor");
              assertThat(action.getComment()).isEqualTo("Missing supporting information");
            });
    assertThat(transaction.execute(status -> service.diagram(instance.id())).activities())
        .filteredOn(activity -> "review".equals(activity.activityId()))
        .singleElement()
        .satisfies(activity -> assertThat(activity.status()).isEqualTo("REJECTED"));
  }

  @Test
  void theStarterCanWithdrawWithoutHavingAdministrativeCancellationPermission() {
    var instance = start(publish("${initiator}", null));
    authorize("actor", "tenant", false, List.of("workflow.instances.started.view"));
    var result =
        transaction.execute(
            status -> service.withdraw(instance.id(), "Change application details"));

    assertThat(result.status()).isEqualTo("WITHDRAWN");
    assertThat(actions("WITHDRAWN"))
        .singleElement()
        .satisfies(
            action -> {
              assertThat(action.getActorId()).isEqualTo("actor");
              assertThat(action.getTaskId()).isNull();
            });
    assertThat(actions("CANCELED")).isEmpty();
    assertThat(transaction.execute(status -> service.diagram(instance.id())).activities())
        .filteredOn(activity -> "review".equals(activity.activityId()))
        .singleElement()
        .satisfies(activity -> assertThat(activity.status()).isEqualTo("WITHDRAWN"));
  }

  @Test
  void anotherActorCannotRejectAnAssignedTaskEvenWithTheApprovalOperationPermission() {
    var instance = start(publish("${initiator}", null));
    String task = taskId(instance);
    authorize("another-actor", "tenant", false, List.of("workflow.tasks.complete"));
    assertForbidden(() -> service.reject(task, "Not my task"));
    assertThat(actions("REJECTED")).isEmpty();
    assertThat(engine.getTaskService().createTaskQuery().taskId(task).count()).isEqualTo(1);
  }

  @Test
  void anAdministratorCannotImpersonateTheStarterByUsingWithdrawal() {
    var instance = start(publish("${initiator}", null));
    authorize("another-actor", "tenant", true, List.of());
    assertForbidden(() -> service.withdraw(instance.id(), "Not my application"));
    assertThat(actions("WITHDRAWN")).isEmpty();
  }

  @Test
  void aMissingReasonDoesNotClaimTheCandidateTaskOrWriteAnAction() {
    var instance = start(publish(null, "actor"));
    String task = taskId(instance);
    assertThatThrownBy(() -> transaction.execute(status -> service.reject(task, " \t ")))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("驳回原因");
    assertThat(engine.getTaskService().createTaskQuery().taskId(task).singleResult().getAssignee())
        .isNull();
    assertThat(actions("REJECTED")).isEmpty();
    assertThat(engine.getTaskService().getTaskComments(task)).isEmpty();
  }

  @Test
  void beingTheAssigneeDoesNotGrantAnAbsentApprovalOperationPermission() {
    var instance = start(publish("${initiator}", null));
    String task = taskId(instance);
    authorize("actor", "tenant", false, List.of("workflow.tasks.view"));
    assertForbidden(() -> service.reject(task, "No operation permission"));
    assertThat(actions("REJECTED")).isEmpty();
  }

  @Test
  void taskAndInstanceActionsCannotCrossTheSelectedTenantEvenForAnAdministrator() {
    var instance = start(publish("${initiator}", null));
    String task = taskId(instance);
    authorize("actor", "another-tenant", true, List.of());
    for (Supplier<?> action :
        List.<Supplier<?>>of(
            () -> service.reject(task, "Wrong tenant"),
            () -> service.withdraw(instance.id(), "Wrong tenant"),
            () -> service.cancel(instance.id(), "Wrong tenant"))) {
      assertThatThrownBy(() -> transaction.execute(status -> action.get()))
          .isInstanceOfSatisfying(
              WorkflowException.class,
              error -> assertThat(error.getCode()).isEqualTo(Code.NOT_FOUND));
    }
    assertThat(count(WorkflowActionEntity.class)).isEqualTo(1);
  }

  @Test
  void anApprovedInstanceCannotBeWithdrawnOrRejectedAfterItEnded() {
    var instance = start(publish("${initiator}", null));
    String task = taskId(instance);
    transaction.execute(
        status -> service.complete(task, new CompleteTaskCommand(Map.of(), "Accepted")));
    assertThatThrownBy(
            () -> transaction.execute(status -> service.withdraw(instance.id(), "Too late")))
        .isInstanceOfSatisfying(
            WorkflowException.class, error -> assertThat(error.getCode()).isEqualTo(Code.CONFLICT));
    assertThatThrownBy(() -> transaction.execute(status -> service.reject(task, "Too late")))
        .isInstanceOfSatisfying(
            WorkflowException.class,
            error -> assertThat(error.getCode()).isEqualTo(Code.NOT_FOUND));
    assertThat(actions("APPROVED")).hasSize(1);
    assertThat(actions("WITHDRAWN")).isEmpty();
    assertThat(actions("REJECTED")).isEmpty();
  }

  @Test
  void anAlreadyEndedEngineInstanceCannotPretendToHaveBeenCanceled() {
    var instance = start(publish("${initiator}", null));
    engine.getTaskService().complete(taskId(instance));
    assertThatThrownBy(
            () -> transaction.execute(status -> service.cancel(instance.id(), "Too late")))
        .isInstanceOfSatisfying(
            WorkflowException.class, error -> assertThat(error.getCode()).isEqualTo(Code.CONFLICT));
    assertThat(actions("CANCELED")).isEmpty();
    var diagram = transaction.execute(status -> service.diagram(instance.id()));
    assertThat(diagram.instance().status()).isEqualTo("COMPLETED");
    assertThat(diagram.instance().endedAt()).isNotNull();
    assertThat(diagram.activeActivityIds()).isEmpty();
  }

  @Test
  void failureToAssignTheNextTaskRollsBackApprovalCommentVariablesAndBusinessAction() {
    String definition =
        transaction.execute(
            status -> {
              var initial =
                  service.createDefinition(
                      new CreateDefinitionCommand("nextApproval", "Next approval", null, null));
              String xml =
                  initial
                      .bpmnXml()
                      .replace(
                          "<bpmn:sequenceFlow id=\"flow_review_end\" sourceRef=\"review\""
                              + " targetRef=\"end\"/>",
                          """
<bpmn:sequenceFlow id="flow_review_end" sourceRef="review" targetRef="next"/>
<bpmn:userTask id="next" name="Next review" flowable:assignee="${nextApprover}"/>
<bpmn:sequenceFlow id="finish" sourceRef="next" targetRef="end"/>
""");
              service.updateDraft(
                  initial.definition().id(),
                  new UpdateDraftCommand(1, "Next approval", null, null, xml, null));
              service.publish(initial.definition().id(), new PublishDefinitionCommand(2, null));
              return initial.definition().id();
            });
    var instance = start(definition);
    String task = taskId(instance);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        service.complete(
                            task,
                            new CompleteTaskCommand(
                                Map.of("nextApprover", "foreign-user"), "Must roll back"))))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("有效成员");
    assertThat(engine.getTaskService().createTaskQuery().taskId(task).count()).isEqualTo(1);
    assertThat(engine.getTaskService().getTaskComments(task)).isEmpty();
    assertThat(engine.getRuntimeService().hasVariable(instance.id(), "nextApprover")).isFalse();
    assertThat(actions("APPROVED")).isEmpty();
    assertThat(actions("STARTED")).hasSize(1);
  }

  @Test
  void concurrentApprovalAndWithdrawalCommitExactlyOneTerminalOutcome() throws Exception {
    var instance = start(publish("${initiator}", null));
    String task = taskId(instance);
    var barrier = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var results =
          workers.invokeAll(
              List.of(
                  racing(
                      barrier,
                      () ->
                          service.complete(
                              task, new CompleteTaskCommand(Map.of(), "Concurrent approval"))),
                  racing(barrier, () -> service.withdraw(instance.id(), "Concurrent withdrawal"))),
              20,
              TimeUnit.SECONDS);
      var outcomes = List.of(results.get(0).get(), results.get(1).get());
      assertThat(outcomes).filteredOn(value -> !value.startsWith("failed:")).hasSize(1);
      assertThat(outcomes)
          .filteredOn(value -> value.startsWith("failed:"))
          .singleElement()
          .isIn("failed:CONFLICT", "failed:NOT_FOUND");
    }
    assertThat(actions("APPROVED").size() + actions("WITHDRAWN").size()).isEqualTo(1);
    assertThat(
            engine
                .getRuntimeService()
                .createProcessInstanceQuery()
                .processInstanceId(instance.id())
                .count())
        .isZero();
    String state =
        transaction.execute(
            status ->
                SharedEntityManagerCreator.createSharedEntityManager(factory)
                    .find(WorkflowInstanceEntity.class, instance.id())
                    .getStatus());
    assertThat(state).isEqualTo(actions("APPROVED").isEmpty() ? "WITHDRAWN" : "COMPLETED");
  }

  private Callable<String> racing(
      final CyclicBarrier barrier, final Supplier<ProcessInstanceSummary> action) {
    return () -> {
      authorize("actor", "tenant", true, List.of());
      try {
        barrier.await(5, TimeUnit.SECONDS);
        return transaction.execute(status -> action.get()).status();
      } catch (WorkflowException conflict) {
        return "failed:" + conflict.getCode();
      } finally {
        RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
      }
    };
  }

  private ProcessInstanceSummary start(final String definition) {
    return transaction.execute(
        status ->
            service.start(
                definition,
                new StartProcessCommand(1, null, "Lifecycle test", Map.of(), List.of())));
  }

  private String taskId(final ProcessInstanceSummary instance) {
    return engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(instance.id())
        .singleResult()
        .getId();
  }

  private List<WorkflowActionEntity> actions(final String type) {
    return transaction.execute(
        status ->
            SharedEntityManagerCreator.createSharedEntityManager(factory)
                .createQuery(
                    "select a from WorkflowActionEntity a where a.actionType = :type",
                    WorkflowActionEntity.class)
                .setParameter("type", type)
                .getResultList());
  }

  private void assertForbidden(final Supplier<?> action) {
    assertThatThrownBy(() -> transaction.execute(status -> action.get()))
        .isInstanceOfSatisfying(
            WorkflowException.class,
            error -> assertThat(error.getCode()).isEqualTo(Code.FORBIDDEN));
  }
}
