package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowException.Code;
import org.simplepoint.plugin.workflow.api.event.WorkflowHistoryRemovalEvents;
import org.simplepoint.plugin.workflow.api.model.WorkflowHistoryRemoval.Member;
import org.simplepoint.plugin.workflow.api.model.WorkflowHistoryRemoval.Progress;
import org.simplepoint.plugin.workflow.api.model.WorkflowHistoryRemoval.Request;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorkflowHistoryRemovalTest extends WorkflowEngineFixture {
  @Test
  void waitsForBothOwnersAndErasesWorkflowHistoryWhileFencingOldRetries() {
    String definition = publish("${initiator}", null);
    var command = new WorkflowModels.StartProcessCommand(1, null, "Remove me", Map.of(), List.of(), "start-key");
    var created = transaction.execute(status -> service.start(definition, command));
    String task = engine.getTaskService().createTaskQuery().processInstanceId(created.id()).singleResult().getId();
    var terminal = transaction.execute(status ->
        service.complete(task, new WorkflowModels.CompleteTaskCommand(Map.of(), "Private approval", "done")));
    var ended = transaction.execute(status -> service.instances(null, "COMPLETED", 0, 50)).content().getFirst();
    var messages = new ArrayList<EventMessage>();
    var em = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
    var removal = new WorkflowHistoryRemovalService(em, engine.getHistoryService(), message ->
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override public void afterCommit() { messages.add(message); }
        }));
    var request = new Request(UUID.randomUUID(), List.of(new Member(ended.id(), ended.endedAt())));
    var stale = new Request(UUID.randomUUID(), List.of(new Member(ended.id(), ended.endedAt().minusSeconds(1))));
    assertThatThrownBy(() -> transaction.execute(status -> removal.request(stale)))
        .isInstanceOfSatisfying(WorkflowException.class, error ->
            assertThat(error.getCode()).isEqualTo(Code.HISTORY_REMOVAL_BLOCKED));
    assertThat(messages).isEmpty();
    authorize("reader", "tenant", false, List.of("workflow.instances.view"));
    assertThatThrownBy(() -> transaction.execute(status -> removal.request(request)))
        .isInstanceOfSatisfying(WorkflowException.class, error -> assertThat(error.getCode()).isEqualTo(Code.FORBIDDEN));
    authorize("actor", "tenant", true, List.of());
    Progress accepted = transaction.execute(status -> removal.request(request));
    assertThat(accepted).isEqualTo(new Progress(request.id(), 1, 0));
    assertThat(transaction.<Progress>execute(status -> removal.request(request))).isEqualTo(accepted);
    assertThat(messages).hasSize(1);
    var requested = messages.getFirst();
    var personal = WorkflowHistoryRemovalEvents.completed(requested, "personal");
    transaction.executeWithoutResult(status -> removal.completed(personal));
    assertThat(transaction.<Progress>execute(status -> removal.progress(request.id()))).isEqualTo(accepted);
    assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(terminal.id()).count()).isOne();

    var app = WorkflowHistoryRemovalEvents.completed(requested, "app");
    transaction.executeWithoutResult(status -> removal.completed(app));
    transaction.executeWithoutResult(status -> removal.completed(app));
    assertThat(transaction.<Progress>execute(status -> removal.request(request))).isEqualTo(new Progress(request.id(), 1, 1));
    assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(terminal.id()).count()).isZero();
    assertThat(engine.getHistoryService().createHistoricTaskInstanceQuery().taskId(task).count()).isZero();
    assertThat(transaction.<Long>execute(status -> em.createQuery(
        "select count(a) from WorkflowActionEntity a where a.processInstanceId = :id", Long.class)
        .setParameter("id", terminal.id()).getSingleResult())).isZero();
    assertThatThrownBy(() -> transaction.execute(status -> service.start(definition, command)))
        .isInstanceOfSatisfying(WorkflowException.class, error -> assertThat(error.getCode()).isEqualTo(Code.NOT_FOUND));
  }
}
