package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormSubmissionEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationRemovedSubmissionEntity;
import org.simplepoint.plugin.workflow.api.event.WorkflowHistoryRemovalEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Erases the application-owned copy of approval history while retaining its underlying business row. */
@Component
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
public class FormSubmissionRemovalHandler implements EventHandler {
  private final EntityManager entities;
  private final EventPublisher events;

  public FormSubmissionRemovalHandler(EntityManager entities, EventPublisher events) {
    this.entities = entities;
    this.events = events;
  }

  @Override
  public EventSubscription subscription() {
    return WorkflowHistoryRemovalEvents.requestSubscription("app");
  }

  @Override
  @Transactional
  public void handle(EventMessage message) {
    String expected = WorkflowHistoryRemovalEvents.submissionId(message);
    var matches = entities.createQuery("select s.id from ApplicationFormSubmissionEntity s"
            + " where s.tenantId = :tenant and s.processInstanceId = :process", String.class)
        .setParameter("tenant", message.tenantId()).setParameter("process", message.resourceId())
        .setMaxResults(2).getResultList();
    if (matches.size() > 1) throw conflict();
    if (matches.isEmpty()) {
      if (expected != null) requireReceipt(message, expected);
    } else {
      String id = matches.getFirst();
      if (expected != null && !expected.equals(id)) throw conflict();
      erase(message, id);
    }
    events.publish(WorkflowHistoryRemovalEvents.completed(message, "app"));
  }

  private void erase(EventMessage message, String id) {
    var formIds = entities.createQuery("select s.formId from ApplicationFormSubmissionEntity s"
            + " where s.id = :id and s.tenantId = :tenant", String.class)
        .setParameter("id", id).setParameter("tenant", message.tenantId()).getResultList();
    if (formIds.isEmpty()) {
      requireReceipt(message, id);
      return;
    }
    var form = entities.find(ApplicationFormEntity.class, formIds.getFirst(), LockModeType.PESSIMISTIC_WRITE);
    if (form == null || !message.tenantId().equals(form.getTenantId())) throw conflict();
    var row = entities.find(ApplicationFormSubmissionEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
    if (row == null) {
      requireReceipt(message, id);
      return;
    }
    if (!message.tenantId().equals(row.getTenantId()) || !message.resourceId().equals(row.getProcessInstanceId())
        || message.generation() != row.getDefinitionVersion()
        || !List.of("STARTED", "TERMINAL").contains(row.getStep())) {
      throw conflict();
    }
    var successor = entities.createQuery("select s.id from ApplicationFormSubmissionEntity s"
            + " where s.tenantId = :tenant and s.previousSubmissionId = :id", String.class)
        .setParameter("tenant", message.tenantId()).setParameter("id", id)
        .setMaxResults(1).getResultList();
    if (!successor.isEmpty()) throw conflict();
    var receipt = new ApplicationRemovedSubmissionEntity();
    receipt.setId(id);
    receipt.setTenantId(message.tenantId());
    receipt.setProcessInstanceId(message.resourceId());
    receipt.setOperationId(message.operationId());
    entities.persist(receipt);
    entities.remove(row);
  }

  private void requireReceipt(EventMessage message, String id) {
    var receipt = entities.find(ApplicationRemovedSubmissionEntity.class, id);
    if (receipt == null || !message.tenantId().equals(receipt.getTenantId())
        || !message.resourceId().equals(receipt.getProcessInstanceId())
        || !message.operationId().equals(receipt.getOperationId())) {
      throw conflict();
    }
  }

  private static IllegalStateException conflict() {
    return new IllegalStateException("Application submission is unavailable or has a dependent submission");
  }
}
