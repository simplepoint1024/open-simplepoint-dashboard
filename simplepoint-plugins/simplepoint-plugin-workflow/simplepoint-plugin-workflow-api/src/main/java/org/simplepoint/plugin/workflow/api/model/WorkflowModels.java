package org.simplepoint.plugin.workflow.api.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Transport-neutral contracts for workflow definition and runtime operations. */
public final class WorkflowModels {

  private WorkflowModels() {}

  /** Stable page shape independent of Spring Data serialization. */
  public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    /** Normalizes the page content to an immutable non-null list. */
    public PageResult {
      content = content == null ? List.of() : List.copyOf(content);
    }
  }

  /** A latest published process whose start form matches the selected immutable business form. */
  public record BoundDefinition(String id, String name, int version, String description) {}

  /** Creates a stable process identity and its first draft. */
  public record CreateDefinitionCommand(
      String key, String name, String category, String description) {}

  /** Saves a draft when its optimistic revision still matches. */
  public record UpdateDraftCommand(
      long expectedRevision,
      String name,
      String category,
      String description,
      String bpmnXml,
      WorkflowFormContract formContract) {}

  /** Publishes the requested draft revision with an audit comment. */
  public record PublishDefinitionCommand(long expectedRevision, String comment) {}

  /** Definition list projection; header capabilities match database-backed fields only. */
  @org.simplepoint.core.query.TableQuery(
      maxPageSize = 100,
      value = {
        @org.simplepoint.core.query.QueryField("name"),
        @org.simplepoint.core.query.QueryField("key"),
        @org.simplepoint.core.query.QueryField("category"),
        @org.simplepoint.core.query.QueryField("description"),
        @org.simplepoint.core.query.QueryField("status"),
        @org.simplepoint.core.query.QueryField("latestVersion"),
        @org.simplepoint.core.query.QueryField("createdAt"),
        @org.simplepoint.core.query.QueryField("updatedAt")
      })
  public record DefinitionSummary(
      String id,
      String key,
      String name,
      String category,
      String description,
      String status,
      int latestVersion,
      long draftRevision,
      boolean unpublishedChanges,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  /** Definition editor projection including draft, history and capabilities. */
  public record DefinitionDetail(
      DefinitionSummary definition,
      String bpmnXml,
      List<VersionSummary> versions,
      DefinitionPermissions permissions,
      WorkflowFormContract formContract) {}

  /** Server-derived operation capabilities for the current actor. */
  public record DefinitionPermissions(
      boolean canCreate,
      boolean canEdit,
      boolean canPublish,
      boolean canCopyVersion,
      boolean canStart) {}

  /** Server-derived task and instance capabilities for the current actor. */
  public record RuntimePermissions(
      boolean canClaim,
      boolean canComplete,
      boolean canViewAllInstances,
      boolean canCancelInstances,
      boolean canStartInstances) {}

  /** Immutable published version projection. */
  public record VersionSummary(
      String id,
      int version,
      String checksum,
      String comment,
      String flowableProcessDefinitionId,
      String publishedBy,
      Instant publishedAt) {}

  /** Published version and its BPMN source. */
  public record VersionDetail(
      VersionSummary version,
      String bpmnXml,
      String name,
      String category,
      String description,
      WorkflowFormContract formContract) {}

  /** Authorized version-pinned runtime form projection, without engine-internal variables. */
  public record RuntimeForm(
      int definitionVersion,
      WorkflowFormContract formContract,
      WorkflowFormContract.Binding binding,
      Map<String, Object> values,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String processInstanceId,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String businessKey,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String definitionId,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
          java.util.Set<String> protectedVariables) {
    /** Validates and defensively copies the immutable request or snapshot boundary. */
    public RuntimeForm {
      protectedVariables =
          protectedVariables == null
              ? java.util.Set.of()
              : java.util.Set.copyOf(protectedVariables);
    }

    /** Preserves compatibility for callers without optional snapshot or scene metadata. */
    public RuntimeForm(
        int definitionVersion,
        WorkflowFormContract formContract,
        WorkflowFormContract.Binding binding,
        Map<String, Object> values) {
      this(definitionVersion, formContract, binding, values, null, null, null, java.util.Set.of());
    }
  }

  /** Starts a process against the latest or an explicit published version. */
  public record StartProcessCommand(
      Integer version,
      String businessKey,
      String title,
      Map<String, Object> variables,
      List<String> ccUserIds,
      String idempotencyKey) {
    /** Compatibility for existing callers; reliable integrations always supply an operation key. */
    public StartProcessCommand(
        Integer version,
        String businessKey,
        String title,
        Map<String, Object> variables,
        List<String> ccUserIds) {
      this(version, businessKey, title, variables, ccUserIds, null);
    }
  }

  /** Completes a task with constrained variables and an optional comment. */
  public record CompleteTaskCommand(
      Map<String, Object> variables, String comment, String idempotencyKey) {
    /** Compatibility with existing unkeyed task clients. */
    public CompleteTaskCommand(Map<String, Object> variables, String comment) {
      this(variables, comment, null);
    }
  }

  /** Platform-owned process instance projection. */
  public record ProcessInstanceSummary(
      String id,
      String definitionId,
      String definitionKey,
      String definitionName,
      int definitionVersion,
      String businessKey,
      String title,
      String starterId,
      String status,
      Instant startedAt,
      Instant endedAt) {}

  /** Pending or completed work item projection. */
  public record TaskSummary(
      String id,
      String processInstanceId,
      String definitionKey,
      String definitionName,
      String name,
      String taskDefinitionKey,
      String assignee,
      String owner,
      Instant createdAt,
      Instant dueAt,
      Instant completedAt,
      int priority,
      boolean claimable,
      boolean actionable,
      String businessKey,
      String instanceTitle) {}

  /** One historic or active BPMN activity occurrence. */
  public record ActivityState(
      String activityId,
      String activityName,
      String activityType,
      String status,
      Instant startedAt,
      Instant endedAt,
      String assignee,
      String id,
      String taskId) {}

  /** BPMN source plus activity state used by the shared status diagram. */
  public record InstanceDiagram(
      ProcessInstanceSummary instance,
      String bpmnXml,
      List<ActivityState> activities,
      List<String> activeActivityIds,
      long totalActivities,
      boolean historyTruncated) {}
}
