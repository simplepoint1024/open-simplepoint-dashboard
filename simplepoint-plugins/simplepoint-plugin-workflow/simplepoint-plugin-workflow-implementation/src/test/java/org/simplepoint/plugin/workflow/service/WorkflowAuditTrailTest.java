package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowAuditTrail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;

class WorkflowAuditTrailTest extends WorkflowDirectoryFixture {
  @Test
  void processOnlyWithdrawalAndClaimRemainVisibleWithRealActorsAndReasons() {
    String definition = publish(null, "actor");
    var source = transaction.execute(status -> service.start(definition, command()));
    String task =
        engine
            .getTaskService()
            .createTaskQuery()
            .processInstanceId(source.id())
            .singleResult()
            .getId();
    transaction.execute(status -> service.claim(task));
    transaction.execute(status -> service.withdraw(source.id(), "Correct the amount"));
    var result = transaction.execute(status -> service.auditTrail(source.id(), 0, 20));
    assertThat(result.recordedFromStart()).isTrue();
    assertThat(result.events().content())
        .extracting(WorkflowAuditTrail.Event::actionType)
        .containsExactly("WITHDRAWN", "CLAIMED", "STARTED");
    assertThat(result.events().content())
        .allSatisfy(event -> assertThat(event.actorName()).isEqualTo("张三"));
    assertThat(result.events().content().getFirst().comment()).isEqualTo("Correct the amount");
    assertThat(result.events().content().getFirst().activityId()).isNull();
    assertThat(result.events().content().get(1).activityId()).isEqualTo("review");
    assertThat(result.events().content().get(1).activityName()).isNotBlank();
    var first = transaction.execute(status -> service.auditTrail(source.id(), 0, 2));
    var next = transaction.execute(status -> service.auditTrail(source.id(), 1, 2));
    assertThat(first.events().totalElements()).isEqualTo(3);
    assertThat(next.events().content()).hasSize(1);
    assertThat(first.events().content()).doesNotContainAnyElementsOf(next.events().content());
  }

  @Test
  void legacyPartialJournalNeverInventsAnApprovalOrAnOriginalStarterEvent() {
    var source = transaction.execute(status -> service.start(publish("actor", null), command()));
    transaction.executeWithoutResult(
        status -> em.createQuery("delete from WorkflowActionEntity").executeUpdate());
    transaction.execute(status -> service.cancel(source.id(), "Canceled legacy item"));
    var result = transaction.execute(status -> service.auditTrail(source.id(), 0, 20));
    assertThat(result.recordedFromStart()).isFalse();
    assertThat(result.events().content())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.actionType()).isEqualTo("CANCELED");
              assertThat(event.actorName()).isEqualTo("张三");
            });
  }

  @Test
  void approvalIsRecordedOnceAndDisabledIdentityDoesNotFallBackToAnId() {
    var source = transaction.execute(status -> service.start(publish("actor", null), command()));
    String task =
        engine
            .getTaskService()
            .createTaskQuery()
            .processInstanceId(source.id())
            .singleResult()
            .getId();
    transaction.execute(
        status -> service.complete(task, new CompleteTaskCommand(Map.of(), "Accepted")));
    transaction.executeWithoutResult(
        status ->
            em.createNativeQuery("update simpoint_ac_users set enabled=false where id='actor'")
                .executeUpdate());
    var result = transaction.execute(status -> service.auditTrail(source.id(), 0, 20));
    assertThat(result.events().content())
        .extracting(WorkflowAuditTrail.Event::actionType)
        .containsExactly("APPROVED", "STARTED");
    assertThat(result.events().content())
        .allSatisfy(event -> assertThat(event.actorName()).isNull());
  }

  @Test
  void auditUsesTheSameParticipantTenantAndBoundedPageRulesAsProgress() {
    var source = transaction.execute(status -> service.start(publish("actor", null), command()));
    assertThatThrownBy(() -> transaction.execute(status -> service.auditTrail(source.id(), 0, 51)))
        .isInstanceOf(WorkflowException.class);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> service.auditTrail(source.id(), Integer.MAX_VALUE, 20)))
        .isInstanceOf(WorkflowException.class);
    authorize("reviewer", "tenant", false, List.of("workflow.tasks.view"));
    assertThatThrownBy(() -> transaction.execute(status -> service.auditTrail(source.id(), 0, 20)))
        .isInstanceOf(WorkflowException.class);
    authorize("actor", "other-tenant", true, List.of());
    assertThatThrownBy(() -> transaction.execute(status -> service.auditTrail(source.id(), 0, 20)))
        .isInstanceOf(WorkflowException.class);
  }

  private StartProcessCommand command() {
    return new StartProcessCommand(1, null, "Audit", Map.of(), List.of());
  }
}
