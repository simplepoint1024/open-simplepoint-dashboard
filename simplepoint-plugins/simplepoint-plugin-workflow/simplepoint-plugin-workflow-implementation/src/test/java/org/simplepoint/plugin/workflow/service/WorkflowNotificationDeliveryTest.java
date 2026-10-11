package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.notification.api.service.NotificationDeliveryService;
import org.simplepoint.plugin.notification.api.vo.NotificationDeliveryCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.entity.WorkflowNotificationOutboxEntity;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

class WorkflowNotificationDeliveryTest extends WorkflowEngineFixture {
  @Test
  void candidateRoleRecipientsArePagedAndDeduplicatedAgainstExplicitCandidates() {
    List<String> first =
        IntStream.range(0, 200).mapToObj(i -> i == 0 ? "actor" : "member-" + i).toList();
    when(tenants.resolveActiveRoleMemberIds(eq("tenant"), anyCollection(), isNull(), eq(200)))
        .thenReturn(first);
    when(tenants.resolveActiveRoleMemberIds(
            eq("tenant"), anyCollection(), eq("member-199"), eq(200)))
        .thenReturn(List.of("member-200"));
    String definition =
        definition(
            "flowable:candidateUsers=\"actor\" flowable:candidateGroups=\"tenant.approver\"");
    var instance =
        transaction.execute(
            status ->
                service.start(
                    definition,
                    new StartProcessCommand(1, null, "Role review", Map.of(), List.of())));
    var task =
        engine.getTaskService().createTaskQuery().processInstanceId(instance.id()).singleResult();
    List<WorkflowNotificationOutboxEntity> outbox = outbox();
    assertThat(outbox).hasSize(201);
    assertThat(outbox)
        .extracting(WorkflowNotificationOutboxEntity::getTargetUserId)
        .doesNotHaveDuplicates();
    assertThat(outbox)
        .allSatisfy(
            message -> {
              assertThat(message.getTenantId()).isEqualTo("tenant");
              assertThat(message.getLinkUrl())
                  .isEqualTo(WorkflowLinks.task(instance.id(), task.getId()));
            });
    verify(tenants)
        .resolveActiveRoleMemberIds("tenant", List.of("tenant.approver"), "member-199", 200);
  }

  @Test
  void assignedTasksDoNotNotifyCandidateGroupsThatCanNoLongerClaimThem() {
    String definition =
        definition("flowable:assignee=\"actor\" flowable:candidateGroups=\"tenant.approver\"");
    transaction.execute(
        status ->
            service.start(
                definition,
                new StartProcessCommand(1, null, "Assigned review", Map.of(), List.of())));
    assertThat(outbox())
        .singleElement()
        .extracting(WorkflowNotificationOutboxEntity::getTargetUserId)
        .isEqualTo("actor");
    verify(tenants, never())
        .resolveActiveRoleMemberIds(anyString(), anyCollection(), nullable(String.class), anyInt());
  }

  @Test
  void deliveryRetriesPreserveTenantRecipientAndIdempotencyKey() {
    String definition = publish("actor", null);
    transaction.execute(
        status ->
            service.start(
                definition, new StartProcessCommand(1, null, "Review", Map.of(), List.of())));
    var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    var delivery = mock(NotificationDeliveryService.class);
    var commands = ArgumentCaptor.forClass(NotificationDeliveryCommand.class);
    when(delivery.deliver(commands.capture()))
        .thenThrow(new IllegalStateException("temporarily unavailable"))
        .thenReturn("message-id");
    var dispatcher = new WorkflowNotificationOutboxDispatcher(em, delivery);
    transaction.executeWithoutResult(status -> dispatcher.dispatch());
    assertThat(outbox())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getStatus()).isEqualTo("RETRY");
              assertThat(row.getAttempts()).isEqualTo(1);
              assertThat(row.getSentAt()).isNull();
              assertThat(row.getLastError()).contains("temporarily unavailable");
            });
    transaction.executeWithoutResult(
        status -> {
          em.find(WorkflowNotificationOutboxEntity.class, outbox().getFirst().getId())
              .setNextAttemptAt(Instant.EPOCH);
        });
    transaction.executeWithoutResult(status -> dispatcher.dispatch());
    assertThat(outbox())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getStatus()).isEqualTo("SENT");
              assertThat(row.getSentAt()).isNotNull();
              assertThat(row.getLastError()).isNull();
            });
    assertThat(commands.getAllValues()).hasSize(2).containsOnly(commands.getValue());
    assertThat(commands.getValue().tenantId()).isEqualTo("tenant");
    assertThat(commands.getValue().targetUserId()).isEqualTo("actor");
    assertThat(commands.getValue().linkUrl()).startsWith("/my/todos?task=");
  }

  private List<WorkflowNotificationOutboxEntity> outbox() {
    return transaction.execute(
        status ->
            SharedEntityManagerCreator.createSharedEntityManager(factory)
                .createQuery(
                    "from WorkflowNotificationOutboxEntity order by eventKey",
                    WorkflowNotificationOutboxEntity.class)
                .getResultList());
  }

  private String definition(String assignment) {
    return transaction.execute(
        status -> {
          var initial =
              service.createDefinition(
                  new CreateDefinitionCommand("roleReview", "Review", null, null));
          String xml = initial.bpmnXml().replace("flowable:assignee=\"${initiator}\"", assignment);
          service.updateDraft(
              initial.definition().id(),
              new UpdateDraftCommand(1, "Review", null, null, xml, null));
          service.publish(initial.definition().id(), new PublishDefinitionCommand(2, null));
          return initial.definition().id();
        });
  }
}
