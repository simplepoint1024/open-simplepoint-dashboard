package org.simplepoint.plugin.workflow.api.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Administrative batch selection and progress. */
public final class WorkflowHistoryRemoval {
  private WorkflowHistoryRemoval() {}

  public record Member(String id, Instant endedAt) {}

  public record Request(UUID id, List<Member> instances) {}

  public record Progress(UUID id, int total, int completed) {}
}
