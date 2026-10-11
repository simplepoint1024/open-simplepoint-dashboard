package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.entity.WorkflowActionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.springframework.data.domain.PageRequest;

class WorkflowResubmissionTest extends WorkflowDirectoryFixture {
  @Test
  void resubmissionPinsTheOriginalVersionAndPreservesTheOriginalDecisionHistory() {
    String definition = publish("actor", null);
    var source = start(definition, Map.of("amount", 10));
    String task =
        engine
            .getTaskService()
            .createTaskQuery()
            .processInstanceId(source.id())
            .singleResult()
            .getId();
    transaction.execute(status -> service.reject(task, "Please correct the amount"));
    transaction.executeWithoutResult(
        status -> {
          var draft = service.definition(definition);
          service.updateDraft(
              definition,
              new UpdateDraftCommand(
                  draft.definition().draftRevision(),
                  "Changed rules",
                  null,
                  null,
                  draft.bpmnXml(),
                  null));
          service.publish(
              definition,
              new PublishDefinitionCommand(draft.definition().draftRevision() + 1, null));
        });
    var form = transaction.execute(status -> service.resubmissionForm(source.id()));
    assertThat(form.form().definitionVersion()).isEqualTo(1);
    assertThat(form.form().values())
        .containsEntry("amount", 10)
        .doesNotContainKeys(
            "initiator", "workflowDefinitionId", "workflowVersion", "workflowTitle");
    assertThat(form.source().canResubmit()).isTrue();
    var successor =
        transaction.execute(
            status -> service.resubmit(source.id(), command(1, Map.of("amount", 20))));
    assertThat(successor.id()).isNotEqualTo(source.id());
    assertThat(successor.definitionVersion()).isEqualTo(1);
    assertThat(successor.status()).isEqualTo("RUNNING");
    var old = transaction.execute(status -> em.find(WorkflowInstanceEntity.class, source.id()));
    assertThat(old.getStatus()).isEqualTo("REJECTED");
    assertThat(old.getCancelReason()).isEqualTo("Please correct the amount");
    var current =
        transaction.execute(status -> em.find(WorkflowInstanceEntity.class, successor.id()));
    assertThat(current.getPreviousInstanceId()).isEqualTo(source.id());
    assertThat(
            engine
                .getHistoryService()
                .createHistoricTaskInstanceQuery()
                .taskId(task)
                .singleResult()
                .getDeleteReason())
        .isEqualTo("simplepoint:REJECTED");
    var rows =
        transaction.execute(
            status ->
                new WorkflowMatterQueries(em).query("started", Map.of(), PageRequest.of(0, 20)));
    assertThat(rows.content())
        .filteredOn(row -> row.id().equals(source.id()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.canResubmit()).isFalse();
              assertThat(row.nextInstanceId()).isEqualTo(successor.id());
            });
    assertThat(rows.content())
        .filteredOn(row -> row.id().equals(successor.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.previousInstanceId()).isEqualTo(source.id()));
    assertThat(
            transaction.<List<WorkflowActionEntity>>execute(
                status ->
                    em.createQuery(
                            "from WorkflowActionEntity where actionType = 'RESUBMITTED'",
                            WorkflowActionEntity.class)
                        .getResultList()))
        .singleElement()
        .satisfies(action -> assertThat(action.getProcessInstanceId()).isEqualTo(successor.id()));
  }

  @Test
  void withdrawnAndCanceledMattersCanStartExactlyOneLinkedSuccessor() {
    String definition = publish("actor", null);
    for (String outcome : List.of("WITHDRAWN", "CANCELED")) {
      var source = start(definition, Map.of());
      transaction.execute(
          status ->
              outcome.equals("WITHDRAWN")
                  ? service.withdraw(source.id(), "Revise")
                  : service.cancel(source.id(), "Revise"));
      var successor =
          transaction.execute(status -> service.resubmit(source.id(), command(1, Map.of())));
      assertThat(successor.status()).isEqualTo("RUNNING");
      assertThatThrownBy(
              () ->
                  transaction.execute(
                      status -> service.resubmit(source.id(), command(1, Map.of()))))
          .isInstanceOf(WorkflowException.class)
          .hasMessageContaining("已经重新发起");
    }
  }

  @Test
  void otherUsersIncludingAdministratorsCannotResubmitOnBehalfOfTheStarter() {
    var source = start(publish("actor", null), Map.of());
    transaction.execute(status -> service.withdraw(source.id(), "Revise"));
    authorize("reviewer", "tenant", true, List.of());
    assertThatThrownBy(() -> transaction.execute(status -> service.resubmissionForm(source.id())))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("原发起人");
    assertThatThrownBy(
            () ->
                transaction.execute(status -> service.resubmit(source.id(), command(1, Map.of()))))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("原发起人");
    authorize("actor", "other-tenant", true, List.of());
    assertThatThrownBy(() -> transaction.execute(status -> service.resubmissionForm(source.id())))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("不存在");
    authorize("actor", "tenant", false, List.of("workflow.instances.started.view"));
    assertThatThrownBy(
            () ->
                transaction.execute(status -> service.resubmit(source.id(), command(1, Map.of()))))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("权限");
  }

  @Test
  void runningAndApprovedMattersCannotBeReopenedAndVersionTamperingIsRejected() {
    String definition = publish("actor", null);
    var source = start(definition, Map.of());
    assertThatThrownBy(
            () ->
                transaction.execute(status -> service.resubmit(source.id(), command(1, Map.of()))))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("只有驳回");
    String task =
        engine
            .getTaskService()
            .createTaskQuery()
            .processInstanceId(source.id())
            .singleResult()
            .getId();
    transaction.execute(
        status -> service.complete(task, new CompleteTaskCommand(Map.of(), "Approved")));
    assertThatThrownBy(() -> transaction.execute(status -> service.resubmissionForm(source.id())))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("只有驳回");
    var canceled = start(definition, Map.of());
    transaction.execute(status -> service.cancel(canceled.id(), "Revise"));
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> service.resubmit(canceled.id(), command(2, Map.of()))))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("原事项的已发布版本");
  }

  @Test
  void invalidSuccessorRollsBackWithoutConsumingTheSourceOrLosingItsHistory() {
    var source = start(publish("${approver}", null), Map.of("approver", "actor"));
    transaction.execute(status -> service.withdraw(source.id(), "Revise"));
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        service.resubmit(
                            source.id(), command(1, Map.of("approver", "foreign-user")))))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("有效成员");
    assertThat(
            transaction.<Long>execute(
                status ->
                    em.createQuery("select count(i) from WorkflowInstanceEntity i", Long.class)
                        .getSingleResult()))
        .isEqualTo(1L);
    assertThat(engine.getRuntimeService().createProcessInstanceQuery().count()).isZero();
    assertThat(
            transaction
                .execute(status -> service.resubmissionForm(source.id()))
                .source()
                .canResubmit())
        .isTrue();
    assertThat(
            transaction
                .execute(
                    status ->
                        service.resubmit(source.id(), command(1, Map.of("approver", "actor"))))
                .status())
        .isEqualTo("RUNNING");
  }

  @Test
  void concurrentResubmissionsCreateOnlyOneNewInstance() throws Exception {
    var source = start(publish("actor", null), Map.of());
    transaction.execute(status -> service.withdraw(source.id(), "Revise"));
    var barrier = new CyclicBarrier(2);
    Callable<String> action =
        () -> {
          authorize("actor", "tenant", true, List.of());
          try {
            barrier.await(5, TimeUnit.SECONDS);
            return transaction
                .execute(status -> service.resubmit(source.id(), command(1, Map.of())))
                .status();
          } catch (WorkflowException exception) {
            return exception.getCode().name();
          } finally {
            RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
          }
        };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results = pool.invokeAll(List.of(action, action), 20, TimeUnit.SECONDS);
      assertThat(List.of(results.get(0).get(), results.get(1).get()))
          .containsExactlyInAnyOrder("RUNNING", "CONFLICT");
    }
    assertThat(
            transaction.<Long>execute(
                status ->
                    em.createQuery("select count(i) from WorkflowInstanceEntity i", Long.class)
                        .getSingleResult()))
        .isEqualTo(2L);
  }

  @Test
  void aSuspendedOriginalVersionCannotBeReplacedSilentlyByAnotherVersion() {
    var source = start(publish("actor", null), Map.of());
    transaction.execute(status -> service.withdraw(source.id(), "Revise"));
    String definitionId =
        engine
            .getHistoryService()
            .createHistoricProcessInstanceQuery()
            .processInstanceId(source.id())
            .singleResult()
            .getProcessDefinitionId();
    engine.getRepositoryService().suspendProcessDefinitionById(definitionId);
    assertThatThrownBy(() -> transaction.execute(status -> service.resubmissionForm(source.id())))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("原流程版本当前不可用");
  }

  private ProcessInstanceSummary start(String definition, Map<String, Object> values) {
    return transaction.execute(status -> service.start(definition, command(1, values)));
  }

  private StartProcessCommand command(int version, Map<String, Object> values) {
    return new StartProcessCommand(version, "business", "Resubmission", values, List.of());
  }
}
