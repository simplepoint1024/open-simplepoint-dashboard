package org.simplepoint.plugin.personal.service;

import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.api.LegacyEventOwner;
import org.simplepoint.plugin.personal.repository.PersonalRepository;
import org.simplepoint.plugin.workflow.api.event.WorkflowHistoryRemovalEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Purges delivered copies and fences later replay of the removed workflow's notifications. */
@Component
@LegacyEventOwner("personal")
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
public class WorkflowMessageRemovalHandler implements EventHandler {
  private final PersonalRepository repository;
  private final EventPublisher events;

  public WorkflowMessageRemovalHandler(PersonalRepository repository, EventPublisher events) {
    this.repository = repository;
    this.events = events;
  }

  @Override
  public EventSubscription subscription() {
    return WorkflowHistoryRemovalEvents.requestSubscription("personal");
  }

  @Override
  @Transactional
  public void handle(EventMessage event) {
    WorkflowHistoryRemovalEvents.submissionId(event);
    repository.removeWorkflowMessages("TENANT:" + event.tenantId(), event.resourceId());
    events.publish(WorkflowHistoryRemovalEvents.completed(event, "personal"));
  }
}
