package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.entity.WorkflowActionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowEngineSignalEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowNotificationOutboxEntity;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/** Native timers/jobs run without an HTTP request or a fabricated approval actor. */
class WorkflowEngineSignalsTest extends WorkflowEngineFixture {
  @Test
  void aTimerCreatesAPersonalTodoAfterCommitWithoutRequestContext() {
    String id = start("timerBefore", xml -> timer(xml, true));
    assertThat(engine.getTaskService().createTaskQuery().processInstanceId(id).count()).isZero();
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isZero();
    clearRequest();
    fireTimer(id);
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isZero();
    assertThat(count(WorkflowEngineSignalEntity.class)).isPositive();
    dispatcher().dispatch();
    assertThat(notifications())
        .singleElement()
        .satisfies(
            message -> {
              assertThat(message.getTenantId()).isEqualTo("tenant");
              assertThat(message.getTargetUserId()).isEqualTo("actor");
              assertThat(message.getLinkUrl()).startsWith("/my/todos?task=").contains(id);
            });
    assertThat(count(WorkflowEngineSignalEntity.class)).isZero();
    dispatcher().dispatch();
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isEqualTo(1);
  }

  @Test
  void aTimerAfterApprovalFinishesThePlatformInstanceAndNotifiesOnlyOnce() {
    String id = start("timerAfter", xml -> timer(xml, false));
    var task = engine.getTaskService().createTaskQuery().processInstanceId(id).singleResult();
    transaction.executeWithoutResult(
        status -> service.complete(task.getId(), new CompleteTaskCommand(Map.of(), "Approved")));
    assertThat(instance(id).getStatus()).isEqualTo("RUNNING");
    dispatcher().dispatch();
    clearRequest();
    fireTimer(id);
    assertThat(instance(id).getStatus()).isEqualTo("RUNNING");
    dispatcher().dispatch();
    assertThat(instance(id).getStatus()).isEqualTo("COMPLETED");
    assertThat(instance(id).getEndedAt())
        .isEqualTo(
            engine
                .getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(id)
                .singleResult()
                .getEndTime()
                .toInstant());
    assertThat(notifications())
        .filteredOn(row -> row.getEventKey().contains(":completed:"))
        .singleElement()
        .extracting(WorkflowNotificationOutboxEntity::getLinkUrl)
        .isEqualTo(WorkflowLinks.matter(id));
    // STARTED + real user APPROVED; no manufactured "system approval" for the timer.
    assertThat(count(WorkflowActionEntity.class)).isEqualTo(2);
    dispatcher().dispatch();
    assertThat(notifications()).hasSize(2);
  }

  @Test
  void asynchronousJobsAndConcurrentConsumersDeduplicateTheSameTask() throws Exception {
    String id =
        start(
            "asyncReview",
            xml -> xml.replace("<bpmn:userTask id=", "<bpmn:userTask flowable:async=\"true\" id="));
    clearRequest();
    var job = engine.getManagementService().createJobQuery().processInstanceId(id).singleResult();
    assertThat(job).isNotNull();
    engine.getManagementService().executeJob(job.getId());
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> dispatcher().dispatch());
      var second = workers.submit(() -> dispatcher().dispatch());
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
    }
    assertThat(notifications()).hasSize(1);
    assertThat(count(WorkflowEngineSignalEntity.class)).isZero();
  }

  @Test
  void rollbackRemovesTheSignalAlongWithTheEngineTaskAndCompletion() {
    String id = start("rollbackReview", xml -> timer(xml, true));
    var timer =
        engine.getManagementService().createTimerJobQuery().processInstanceId(id).singleResult();
    var job = engine.getManagementService().moveTimerToExecutableJob(timer.getId());
    clearRequest();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      engine.getManagementService().executeJob(job.getId());
                      throw new IllegalStateException("force rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count(WorkflowEngineSignalEntity.class)).isZero();
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isZero();
    assertThat(engine.getTaskService().createTaskQuery().processInstanceId(id).count()).isZero();
    engine.getManagementService().executeJob(job.getId());
    dispatcher().dispatch();
    assertThat(notifications()).hasSize(1);
  }

  @Test
  void failedDirectoryLookupRetriesIndependentlyWithoutBlockingAnotherInstance() {
    String delayed = start("delayedReview", xml -> timer(xml, true));
    String healthy = start("healthyReview", xml -> xml);
    String task =
        engine.getTaskService().createTaskQuery().processInstanceId(healthy).singleResult().getId();
    // A genuine engine completion simulates a committed background transition, not a user decision.
    engine.getTaskService().complete(task);
    fireTimer(delayed);
    when(tenants.resolveActiveMemberIds(eq("tenant"), anyCollection()))
        .thenThrow(new IllegalStateException("directory unavailable"));
    clearRequest();
    dispatcher().dispatch();
    assertThat(instance(healthy).getStatus()).isEqualTo("COMPLETED");
    assertThat(notifications()).noneMatch(row -> row.getLinkUrl().contains(delayed));
    var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    transaction.executeWithoutResult(
        status -> {
          var retries =
              em.createQuery("from WorkflowEngineSignalEntity", WorkflowEngineSignalEntity.class)
                  .getResultList();
          assertThat(retries)
              .isNotEmpty()
              .allSatisfy(
                  row -> {
                    assertThat(row.getAttempts()).isEqualTo(1);
                    assertThat(row.getLastError()).contains("directory unavailable");
                    assertThat(row.getNextAttemptAt()).isAfter(Instant.now());
                    row.setNextAttemptAt(Instant.EPOCH);
                  });
        });
    when(tenants.resolveActiveMemberIds(eq("tenant"), anyCollection()))
        .thenReturn(List.of("actor"));
    dispatcher().dispatch();
    assertThat(count(WorkflowEngineSignalEntity.class)).isZero();
    assertThat(notifications()).filteredOn(row -> row.getLinkUrl().contains(delayed)).hasSize(1);
  }

  @Test
  void invalidBackgroundAssignmentsRollbackInsteadOfLeavingAnInaccessibleTask() {
    String id =
        start(
            "invalidTimerReview",
            xml ->
                timer(xml, true)
                    .replace(
                        "flowable:assignee=\"${initiator}\"",
                        "flowable:candidateUsers=\"outside-tenant\""));
    clearRequest();
    assertThatThrownBy(() -> fireTimer(id))
        .hasRootCauseInstanceOf(org.simplepoint.plugin.workflow.api.WorkflowException.class)
        .rootCause()
        .hasMessageContaining("当前组织");
    assertThat(engine.getTaskService().createTaskQuery().processInstanceId(id).count()).isZero();
    assertThat(count(WorkflowEngineSignalEntity.class)).isZero();
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isZero();
    assertThat(instance(id).getStatus()).isEqualTo("RUNNING");
  }

  @Test
  void pendingSignalsCannotOverwriteAnExplicitWithdrawalOrDuplicateAnImmediateMessage() {
    String id = start("withdrawReview", UnaryOperator.identity());
    transaction.executeWithoutResult(status -> service.withdraw(id, "Changed request"));
    long messages = count(WorkflowNotificationOutboxEntity.class);
    clearRequest();
    dispatcher().dispatch();
    assertThat(instance(id).getStatus()).isEqualTo("WITHDRAWN");
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isEqualTo(messages);
    assertThat(count(WorkflowEngineSignalEntity.class)).isZero();
  }

  private String start(String key, UnaryOperator<String> transform) {
    return transaction.execute(
        status -> {
          var draft =
              service.createDefinition(new CreateDefinitionCommand(key, "Review", null, null));
          service.updateDraft(
              draft.definition().id(),
              new UpdateDraftCommand(
                  1, "Review", null, null, transform.apply(draft.bpmnXml()), null));
          service.publish(draft.definition().id(), new PublishDefinitionCommand(2, null));
          return service
              .start(
                  draft.definition().id(),
                  new StartProcessCommand(1, null, "Review", Map.of(), List.of()))
              .id();
        });
  }

  private String timer(String xml, boolean before) {
    String source = before ? "start" : "review";
    String target = before ? "review" : "end";
    return xml.replace(
            "sourceRef=\"" + source + "\" targetRef=\"" + target + "\"",
            "sourceRef=\"" + source + "\" targetRef=\"wait\"")
        .replace(
            "</bpmn:process>",
            """
              <bpmn:intermediateCatchEvent id="wait"><bpmn:timerEventDefinition>
                <bpmn:timeDuration>PT1H</bpmn:timeDuration>
              </bpmn:timerEventDefinition></bpmn:intermediateCatchEvent>
              <bpmn:sequenceFlow id="after_wait" sourceRef="wait" targetRef="%s"/>
            </bpmn:process>
            """
                .formatted(target));
  }

  private void fireTimer(String id) {
    var timer =
        engine.getManagementService().createTimerJobQuery().processInstanceId(id).singleResult();
    assertThat(timer).isNotNull();
    var job = engine.getManagementService().moveTimerToExecutableJob(timer.getId());
    engine.getManagementService().executeJob(job.getId());
  }

  private WorkflowEngineSignalDispatcher dispatcher() {
    return new WorkflowEngineSignalDispatcher(
        SharedEntityManagerCreator.createSharedEntityManager(factory), service, manager);
  }

  private WorkflowInstanceEntity instance(String id) {
    return transaction.execute(
        status ->
            SharedEntityManagerCreator.createSharedEntityManager(factory)
                .find(WorkflowInstanceEntity.class, id));
  }

  private List<WorkflowNotificationOutboxEntity> notifications() {
    return transaction.execute(
        status ->
            SharedEntityManagerCreator.createSharedEntityManager(factory)
                .createQuery(
                    "from WorkflowNotificationOutboxEntity", WorkflowNotificationOutboxEntity.class)
                .getResultList());
  }

  private void clearRequest() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }
}
