package org.simplepoint.plugin.workflow.api.model;

import java.time.Instant;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;

/**
 * Personal work history, not a copy of engine tasks. A process-only action has no taskId. Outcome
 * describes this row; instanceStatus describes the process now. Null identity names mean
 * unavailable, never permission to display an internal ID as a person's name.
 */
@TableQuery(
    maxPageSize = 100,
    value = {
      @QueryField("title"), @QueryField("definitionName"), @QueryField("taskName"),
      @QueryField("starterName"), @QueryField("assigneeName"), @QueryField("actorName"),
      @QueryField("outcome"), @QueryField("instanceStatus"), @QueryField("createdAt"),
      @QueryField("dueAt"), @QueryField("handledAt"), @QueryField("priority")
    })
public record WorkflowInboxItem(
    String id,
    String taskId,
    String processInstanceId,
    String rowType,
    String title,
    String definitionName,
    String taskName,
    String starterName,
    String assigneeName,
    String actorName,
    String outcome,
    String instanceStatus,
    Instant createdAt,
    Instant dueAt,
    Instant handledAt,
    Integer priority,
    String comment,
    boolean handledByMe,
    boolean assigned,
    boolean claimable,
    boolean actionable) {}
