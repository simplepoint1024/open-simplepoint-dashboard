package org.simplepoint.app.form.service;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.app.form.entity.ApplicationFormReleaseReadModel;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.core.schema.ProjectionTableSchema;
import org.simplepoint.data.jpa.ProjectionPages;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.app.form.service.FormReleaseCancellation;
import org.simplepoint.plugin.app.form.service.FormRequestContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorized publication history and recovery actions using the standard platform table contract. */
@Service
@Transactional(readOnly = true)
public class ApplicationFormReleaseCatalog {
  @org.springframework.beans.factory.annotation.Value("${simplepoint.events.enabled:false}")
  private boolean lifecycleEnabled;

  private final EntityManager entityManager;
  private final ApplicationFormService forms;
  private final FormRequestContext context;

  /** Creates the bounded read-side service. */
  public ApplicationFormReleaseCatalog(
      final EntityManager entityManager, final ApplicationFormService forms,
      final FormRequestContext context
  ) {
    this.entityManager = entityManager;
    this.forms = forms;
    this.context = context;
  }

  /** Public projection, without retry keys, execution leases or physical model identities. */
  @TableQuery(value = {@QueryField("pageName"), @QueryField("formVersion"),
      @QueryField("status"), @QueryField("updatedAt")}, maxPageSize = 100)
  public record ReleaseRow(String id, String pageId, String pageName, int formVersion,
      String status, String errorCode, Instant updatedAt, boolean canCancel) {
  }

  /** Lists all saved intents, including those interrupted before their first DNA call. */
  public Page<ReleaseRow> page(
      final String applicationId, final String formId,
      final Map<String, String> filters, final Pageable pageable
  ) {
    String tenant = authorize(applicationId, formId);
    return ProjectionPages.query(entityManager, ApplicationFormReleaseReadModel.class,
        ReleaseRow.class, filters, pageable, "updatedAt", Set.of("pageName"),
        (cb, query, root) -> cb.and(cb.equal(root.get("tenantId"), tenant),
            cb.equal(root.get("applicationId"), applicationId), cb.equal(root.get("formId"), formId)),
        row -> new ReleaseRow(row.getId(), row.getPageId(), row.getPageName(), row.getFormVersion(),
            row.getStatus(), row.getErrorCode(), row.getUpdatedAt(), canPublish()
                && FormReleaseCancellation.allowed(ApplicationFormPageBindingStatus.valueOf(row.getStatus()), row.getAttempt(),
                    row.getPolicyStatus() == null ? null : FormWritePolicyPublicationStatus.valueOf(row.getPolicyStatus()))));
  }

  /** Returns authorized recovery actions; runtime reads retain independent page data permissions. */
  public Map<String, Object> schema(final String applicationId, final String formId) {
    authorize(applicationId, formId);
    Map<String, Object> result = new LinkedHashMap<>(ProjectionTableSchema.schema(ReleaseRow.class,
        "app.release", Map.of("status", Arrays.stream(ApplicationFormPageBindingStatus.values())
            .map(status -> new ProjectionTableSchema.Choice(status.name(), "i18n:app.release.status." + status.name())).toList())));
    List<Map<String, Object>> actions = new ArrayList<>();
    if (canPublish()) {
      actions.add(action("resume", "app.release.resume", 0));
      actions.add(action("cancel", "app.release.cancel", 1));
      actions.add(action("inspection", "app.release.inspection", 2));
      if (lifecycleEnabled) {
        Map<String, Object> lifecycle = new LinkedHashMap<>(action("lifecycle", "app.resource.lifecycle", 4));
        lifecycle.put("icon", "CloudServerOutlined");
        actions.add(lifecycle);
      }
    }
    actions.add(action("data", "app.publication.viewData", 3));
    result.put("buttons", List.copyOf(actions));
    return result;
  }

  private String authorize(final String applicationId, final String formId) {
    var actor = context.requireActor();
    forms.get(applicationId, formId);
    return actor.tenantId();
  }

  private static boolean canPublish() {
    var actor = AuthorizationContextHolder.getContext();
    return Boolean.TRUE.equals(actor.getIsAdministrator())
        || actor.getResources() != null && actor.getResources().contains("app.workbench.forms.publish");
  }

  private static Map<String, Object> action(final String key, final String label, final int order) {
    return Map.of("key", key, "text", "i18n:" + label, "sort", order,
        "argumentMinSize", 1, "argumentMaxSize", 1);
  }
}
