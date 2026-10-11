package org.simplepoint.plugin.workflow.api.model;

import java.time.Instant;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;

/** A personal matter with server-derived row operations, independent of management navigation. */
@TableQuery(
    maxPageSize = 100,
    value = {
      @QueryField("title"), @QueryField("definitionName"), @QueryField("definitionVersion"),
      @QueryField("businessKey"), @QueryField("starterName"), @QueryField("status"),
      @QueryField("startedAt"), @QueryField("endedAt")
    })
public record WorkflowMatter(
    String id,
    String definitionId,
    String title,
    String definitionName,
    int definitionVersion,
    String businessKey,
    String starterName,
    String status,
    Instant startedAt,
    Instant endedAt,
    boolean canWithdraw,
    boolean canCancel,
    boolean canResubmit,
    String previousInstanceId,
    String nextInstanceId) {}
