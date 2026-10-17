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
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.core.schema.ProjectionTableSchema;
import org.simplepoint.data.jpa.ProjectionPages;
import org.simplepoint.plugin.app.form.service.FormRequestContext;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Standard resource table for one's page-owned submissions; workflow operator views remain independent. */
@Service
@Transactional(readOnly = true)
public class ApplicationFormSubmissionCatalog {
  private final EntityManager entityManager;
  private final PublishedApplicationFormResolver forms;
  private final FormRequestContext actors;
  public ApplicationFormSubmissionCatalog(EntityManager entityManager, PublishedApplicationFormResolver forms, FormRequestContext actors) {
    this.entityManager = entityManager;
    this.forms = forms;
    this.actors = actors;
  }

  @TableQuery(value = {@QueryField("title"), @QueryField("status"), @QueryField("processStatus"),
      @QueryField("formVersion"), @QueryField("createdAt"), @QueryField("updatedAt")}, maxPageSize = 100)
  public record SubmissionRow(String id, String title, int formVersion, int definitionVersion,
      String status, String processStatus, Instant createdAt, Instant updatedAt,
      String processInstanceId, boolean canSnapshot, boolean canRetry, boolean canResubmit) { }

  public Page<SubmissionRow> page(String applicationId, String pageId, Map<String, String> filters, Pageable page) {
    var form = forms.resolve(applicationId, pageId);
    var actor = actors.requireActor();
    boolean start = forms.capabilities(form).canStartProcess();
    return ProjectionPages.query(entityManager, ApplicationFormSubmissionReadModel.class, SubmissionRow.class, filters, page,
        "createdAt", Set.of("title"), (cb, query, root) -> cb.and(cb.equal(root.get("tenantId"), actor.tenantId()),
            cb.equal(root.get("applicationId"), applicationId), cb.equal(root.get("pageId"), pageId), cb.equal(root.get("actorId"), actor.actorId())),
        row -> new SubmissionRow(row.getId(), row.getTitle(), row.getFormVersion(), row.getDefinitionVersion(), row.getStatus(),
            row.getProcessStatus(), row.getCreatedAt(), row.getUpdatedAt(), row.getProcessInstanceId(), row.isSnapshotAvailable(),
            start && Set.of("RETRY", "ATTENTION").contains(row.getStatus()),
            start && row.getProcessStatus() != null && Set.of("REJECTED", "WITHDRAWN", "CANCELED").contains(row.getProcessStatus())));
  }

  public Map<String, Object> schema(String applicationId, String pageId) {
    var form = forms.resolve(applicationId, pageId);
    Map<String, Object> result = new LinkedHashMap<>(ProjectionTableSchema.schema(SubmissionRow.class, "app.submission", Map.of(
        "status", choices("ACCEPTED", "PROCESSING", "RETRY", "ATTENTION", "SUCCEEDED"),
        "processStatus", choices("RUNNING", "COMPLETED", "REJECTED", "WITHDRAWN", "CANCELED"))));
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
    return java.util.Arrays.stream(values).map(value -> new ProjectionTableSchema.Choice(value, "i18n:app.submission.status." + value)).toList();
  }
  private static Map<String, Object> action(String key, String icon, int sort) {
    return Map.of("key", key, "icon", icon, "text", "i18n:app.submission." + key, "sort", sort, "argumentMinSize", 1, "argumentMaxSize", 1);
  }
}
