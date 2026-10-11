package org.simplepoint.plugin.workflow.api.model;

import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.InstanceDiagram;

/** Personal progress header and the version-pinned diagram, without raw identity labels. */
public record WorkflowProgress(WorkflowMatter matter, InstanceDiagram diagram) {
  /** Null means a deleted/unavailable identity; the client must not substitute an internal ID. */
  public record IdentityLabel(String name) {}

  /** One execution occurrence, not an aggregate by BPMN node ID. */
  public record NodeExecution(
      String id,
      String activityId,
      String activityName,
      String activityType,
      String taskId,
      String status,
      boolean assigned,
      String assigneeName,
      String actorName,
      String outcome,
      String comment,
      Instant startedAt,
      Instant endedAt,
      List<IdentityLabel> candidateUsers,
      List<IdentityLabel> candidateRoles) {}
}
