package org.simplepoint.app.form.service;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.app.form.entity.ApplicationFormSubmissionReadModel;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TablePagination;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.core.schema.ProjectionTableSchema;
import org.simplepoint.data.jpa.ProjectionPages;
import org.simplepoint.plugin.app.form.service.FormRequestContext;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Page-owned submission history: personal history or evidence for one authorized business record.
 * Workflow mutation and participation permissions remain independent.
 */
@Service
@Transactional(readOnly = true)
public class ApplicationFormSubmissionCatalog {
  private final EntityManager entityManager;
  private final PublishedApplicationFormResolver forms;
  private final FormRequestContext actors;
  private final org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordService records;

  /** Uses the APP read model with tenant, actor and page authorization. */
  public ApplicationFormSubmissionCatalog(
      EntityManager entityManager,
      PublishedApplicationFormResolver forms,
      FormRequestContext actors,
      org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordService records) {
    this.entityManager = entityManager;
    this.forms = forms;
    this.actors = actors;
    this.records = records;
  }

  /** Safe submission projection with independently derived available actions. */
  @TableQuery(
      value = {
        @QueryField("title"),
        @QueryField("status"),
        @QueryField("processStatus"),
        @QueryField("formVersion"),
        @QueryField("createdAt"),
        @QueryField("updatedAt")
      },
      maxPageSize = TablePagination.MAX_PAGE_SIZE)
  public record SubmissionRow(
      String id,
      String title,
      int formVersion,
      int definitionVersion,
      String status,
      String processStatus,
      Instant createdAt,
      Instant updatedAt,
      String processInstanceId,
      boolean canSnapshot,
      boolean canProgress,
      boolean canRetry,
      boolean canResubmit) {}

  /** Filters tenant/page and actor or an authorized record before sorting, counts and paging. */
  public Page<SubmissionRow> page(
      String applicationId, String pageId, String recordId, Map<String, String> filters, Pageable page) {
    var form = forms.resolve(applicationId, pageId);
    var actor = actors.requireActor();
    // A record-scoped history must pass the same row/data-scope check as its detail view.
    if (recordId != null) records.get(applicationId, pageId, recordId);
    boolean start = forms.capabilities(form).canStartProcess();
    return ProjectionPages.query(
        entityManager,
        ApplicationFormSubmissionReadModel.class,
        SubmissionRow.class,
        filters,
        page,
        "createdAt",
        Set.of("title"),
        (cb, query, root) ->
            cb.and(
                cb.equal(root.get("tenantId"), actor.tenantId()),
                cb.equal(root.get("applicationId"), applicationId),
                cb.equal(root.get("pageId"), pageId),
                recordId == null ? cb.equal(root.get("actorId"), actor.actorId())
                    : cb.equal(root.get("recordId"), recordId)),
        row ->
            new SubmissionRow(
                row.getId(),
                row.getTitle(),
                row.getFormVersion(),
                row.getDefinitionVersion(),
                row.getStatus(),
                row.getProcessStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getProcessInstanceId(),
                row.isSnapshotAvailable(),
                actor.actorId().equals(row.getActorId()) && row.getProcessInstanceId() != null,
                start && actor.actorId().equals(row.getActorId()) && Set.of("RETRY", "ATTENTION").contains(row.getStatus()),
                start
                    && actor.actorId().equals(row.getActorId())
                    && row.getProcessStatus() != null
                    && Set.of("REJECTED", "WITHDRAWN", "CANCELED")
                        .contains(row.getProcessStatus())));
  }

  /** Uses the shared schema renderer and current process-start capability. */
  public Map<String, Object> schema(String applicationId, String pageId) {
    var form = forms.resolve(applicationId, pageId);
    final Map<String, Object> result =
        new LinkedHashMap<>(
            ProjectionTableSchema.schema(
                SubmissionRow.class,
                "app.submission",
                Map.of(
                    "status",
                        choices(
                            "ACCEPTED", "PROCESSING", "RETRY", "ATTENTION", "FAILED", "SUCCEEDED"),
                    "processStatus",
                        choices("RUNNING", "COMPLETED", "REJECTED", "WITHDRAWN", "CANCELED"))));
    List<Map<String, Object>> actions = new ArrayList<>();
    actions.add(action("snapshot", "FileSearchOutlined", 0));
    actions.add(action("progress", "ApartmentOutlined", 1));
    if (forms.capabilities(form).canStartProcess()) {
      actions.add(action("retry", "ReloadOutlined", 2));
      actions.add(action("resubmit", "SendOutlined", 3));
    }
    result.put("buttons", actions);
    return result;
  }

  private static List<ProjectionTableSchema.Choice> choices(String... values) {
    return java.util.Arrays.stream(values)
        .map(
            value -> new ProjectionTableSchema.Choice(value, "i18n:app.submission.status." + value))
        .toList();
  }

  private static Map<String, Object> action(String key, String icon, int sort) {
    return Map.of(
        "key",
        key,
        "icon",
        icon,
        "text",
        "i18n:app.submission." + key,
        "sort",
        sort,
        "argumentMinSize",
        1,
        "argumentMaxSize",
        1);
  }
}
