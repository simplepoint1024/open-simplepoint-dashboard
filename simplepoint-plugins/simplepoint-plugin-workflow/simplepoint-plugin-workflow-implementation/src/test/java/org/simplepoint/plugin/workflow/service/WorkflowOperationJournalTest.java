package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowException.Code;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowOperationReceiptEntity;

/** Real engine transitions and receipt persistence share one transaction, including racing retries. */
class WorkflowOperationJournalTest extends WorkflowEngineFixture {
  @Test
  void exactStartAndCompletionReplayAfterTheTaskHasDisappeared() {
    String definition = publish("${initiator}", null);
    var command = command("start-key", "Original");
    var created = transaction.<ProcessInstanceSummary>execute(status -> service.start(definition, command));
    assertThat(transaction.<ProcessInstanceSummary>execute(status -> service.start(definition, command))).isEqualTo(created);
    String task = engine.getTaskService().createTaskQuery().processInstanceId(created.id()).singleResult().getId();
    var complete = new CompleteTaskCommand(Map.of(), "Approved", "complete-key");
    var completed = transaction.<ProcessInstanceSummary>execute(status -> service.complete(task, complete));
    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(transaction.<ProcessInstanceSummary>execute(status -> service.complete(task, complete))).isEqualTo(completed);
    assertThat(transaction.<ProcessInstanceSummary>execute(status -> service.start(definition, command))).isEqualTo(created);
    assertThat(count(WorkflowInstanceEntity.class)).isEqualTo(1);
    assertThat(count(WorkflowOperationReceiptEntity.class)).isEqualTo(2);
    assertThatThrownBy(() -> transaction.<ProcessInstanceSummary>execute(status -> service.start(definition, command("start-key", "Changed"))))
        .isInstanceOfSatisfying(WorkflowException.class, failure -> assertThat(failure.getCode()).isEqualTo(Code.CONFLICT));
    authorize("another", "tenant", true, List.of());
    assertThatThrownBy(() -> transaction.<ProcessInstanceSummary>execute(status -> service.start(definition, command)))
        .isInstanceOfSatisfying(WorkflowException.class, failure -> assertThat(failure.getCode()).isEqualTo(Code.CONFLICT));
  }

  @Test
  void rejectedRoundAndLinkedResubmissionHaveIndependentReplayableConfirmations() {
    String definition = publish("${initiator}", null);
    var created = transaction.<ProcessInstanceSummary>execute(status -> service.start(definition, command("first", "Round one")));
    String task = engine.getTaskService().createTaskQuery().processInstanceId(created.id()).singleResult().getId();
    var rejected = transaction.<ProcessInstanceSummary>execute(status -> service.reject(task, "Revise the details", "reject-key"));
    assertThat(transaction.<ProcessInstanceSummary>execute(status -> service.reject(task, "Revise the details", "reject-key"))).isEqualTo(rejected);
    var nextCommand = command("resubmit-key", "Round two");
    var next = transaction.<ProcessInstanceSummary>execute(status -> service.resubmit(created.id(), nextCommand));
    assertThat(transaction.<ProcessInstanceSummary>execute(status -> service.resubmit(created.id(), nextCommand))).isEqualTo(next);
    var withdrawn = transaction.<ProcessInstanceSummary>execute(status -> service.withdraw(next.id(), "Withdraw", "withdraw-key"));
    assertThat(transaction.<ProcessInstanceSummary>execute(status -> service.withdraw(next.id(), "Withdraw", "withdraw-key"))).isEqualTo(withdrawn);
    var last = transaction.<ProcessInstanceSummary>execute(status -> service.resubmit(next.id(), command("last-round", "Round three")));
    var canceled = transaction.<ProcessInstanceSummary>execute(status -> service.cancel(last.id(), "Cancel", "cancel-key"));
    assertThat(transaction.<ProcessInstanceSummary>execute(status -> service.cancel(last.id(), "Cancel", "cancel-key"))).isEqualTo(canceled);
    assertThat(count(WorkflowInstanceEntity.class)).isEqualTo(3);
  }

  @Test
  void rollbackLeavesNeitherEngineInstanceNorCommittedReceipt() {
    String definition = publish("${initiator}", null);
    var command = command("rollback-key", "Rollback");
    transaction.executeWithoutResult(status -> {
      service.start(definition, command);
      status.setRollbackOnly();
    });
    assertThat(count(WorkflowOperationReceiptEntity.class)).isZero();
    assertThat(count(WorkflowInstanceEntity.class)).isZero();
    assertThat(engine.getRuntimeService().createProcessInstanceQuery().count()).isZero();
    assertThat(transaction.<ProcessInstanceSummary>execute(status -> service.start(definition, command))).isNotNull();
    assertThat(count(WorkflowOperationReceiptEntity.class)).isEqualTo(1);
  }

  @Test
  void concurrentStartsAndTaskCompletionsCommitOnce() throws Exception {
    String definition = publish("${initiator}", null);
    var command = command("concurrent-key", "Concurrent");
    try (var workers = Executors.newFixedThreadPool(2)) {
      var barrier = new CyclicBarrier(2);
      java.util.concurrent.Callable<String> start = () -> {
        authorize("actor", "tenant", true, List.of());
        try {
          barrier.await(10, TimeUnit.SECONDS);
          return transaction.<ProcessInstanceSummary>execute(status -> service.start(definition, command)).id();
        } finally { RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY); }
      };
      var first = workers.submit(start); var second = workers.submit(start);
      String instance = first.get(30, TimeUnit.SECONDS);
      assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(instance);
      String task = engine.getTaskService().createTaskQuery().processInstanceId(instance).singleResult().getId();
      var completion = new CompleteTaskCommand(Map.of(), "Approved once", "concurrent-completion");
      java.util.concurrent.Callable<String> complete = () -> {
        authorize("actor", "tenant", true, List.of());
        try {
          barrier.await(10, TimeUnit.SECONDS);
          return transaction.<ProcessInstanceSummary>execute(status -> service.complete(task, completion)).status();
        } finally { RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY); }
      };
      var approved = workers.submit(complete); var replay = workers.submit(complete);
      assertThat(approved.get(30, TimeUnit.SECONDS)).isEqualTo("COMPLETED");
      assertThat(replay.get(30, TimeUnit.SECONDS)).isEqualTo("COMPLETED");
    }
    assertThat(count(WorkflowInstanceEntity.class)).isEqualTo(1);
    assertThat(count(WorkflowOperationReceiptEntity.class)).isEqualTo(2);
  }

  private static StartProcessCommand command(String key, String title) {
    return new StartProcessCommand(1, "business", title, Map.of(), List.of(), key);
  }
}
