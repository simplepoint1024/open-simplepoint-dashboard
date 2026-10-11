package org.simplepoint.plugin.workflow.api.model;

import java.time.Instant;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;

/** Human actions recorded since instance creation; legacy partial coverage is explicit. */
public record WorkflowAuditTrail(PageResult<Event> events, boolean recordedFromStart) {
  /** An immutable platform action, distinct from automatic engine activity history. */
  public record Event(
      String id,
      String actionType,
      String actorName,
      String comment,
      String activityId,
      String activityName,
      Instant occurredAt) {}
}
