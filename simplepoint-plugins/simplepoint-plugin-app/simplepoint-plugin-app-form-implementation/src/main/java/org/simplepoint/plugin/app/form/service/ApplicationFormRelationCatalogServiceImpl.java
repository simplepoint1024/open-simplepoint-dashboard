package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.simplepoint.api.locale.LocalizedText;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetMetadata;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetOption;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetPage;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRelationCatalogService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplicationPage;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** APP-owned candidate query. No cross-module tables, record reads or post-pagination filtering. */
@Service
@Transactional(readOnly = true)
public class ApplicationFormRelationCatalogServiceImpl
    implements ApplicationFormRelationCatalogService {
  private static final String FROM =
      """
      from WorkspaceApplicationPage p
      join WorkspaceApplication a on a.id = p.applicationId and a.tenantId = p.tenantId
      join ApplicationFormEntity f on f.id = p.formId and f.tenantId = p.tenantId
        and f.applicationId = p.applicationId
      join ApplicationFormVersionEntity v on v.formId = f.id and v.tenantId = p.tenantId
        and v.versionNumber = p.formVersion
      where p.tenantId = :tenant and p.status = 'PUBLISHED' and f.status <> :archived
      and p.resourceId is not null and p.resourceCode is not null and f.physicalModelId is not null
      and f.publishedVersion >= p.formVersion and v.physicalModelVersion > 0
        and v.storageChecksum is not null
      and exists (select b.id from ApplicationFormPageBindingEntity b where b.tenantId = p.tenantId
        and b.applicationId = p.applicationId and b.pageId = p.id and b.formId = p.formId
        and b.formVersion = p.formVersion and b.resourceId = p.resourceId
        and b.resourceCode = p.resourceCode and b.status = :active)
      """;
  private final EntityManager em;
  private final FormRequestContext context;
  private final ApplicationFormService forms;
  private final FormRelationTargetResolver targets;
  private final DnaInvocationContextFactory contexts;

  /** Binds source-form authorization, target discovery and field-policy projection. */
  public ApplicationFormRelationCatalogServiceImpl(
      EntityManager em,
      FormRequestContext context,
      ApplicationFormService forms,
      FormRelationTargetResolver targets,
      DnaInvocationContextFactory contexts) {
    this.em = em;
    this.context = context;
    this.forms = forms;
    this.targets = targets;
    this.contexts = contexts;
  }

  @Override
  public FormRelationTargetPage search(
      String applicationId, String formId, String keyword, int page, int size) {
    if (page < 0
        || size < 1
        || size > 100
        || (long) page * size > Integer.MAX_VALUE
        || keyword != null && keyword.codePointCount(0, keyword.length()) > 200) {
      throw invalid();
    }
    Query query = authorized(applicationId, formId);
    String text = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
    if (!text.isEmpty()) {
      query =
          query.with(
              " and (lower(p.name) like :search escape '!' or lower(a.name) like :search escape '!'"
                  + " or lower(f.name) like :search escape '!')",
              "search",
              "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    }
    long total =
        bind(em.createQuery("select count(p.id) " + query.hql(), Long.class), query)
            .getSingleResult();
    return new FormRelationTargetPage(page, size, total, options(query, page * size, size));
  }

  @Override
  public List<FormRelationTargetOption> resolve(
      String applicationId, String formId, List<String> resourceIds) {
    if (resourceIds == null
        || resourceIds.size() > 100
        || resourceIds.stream()
            .anyMatch(id -> id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) {
      throw invalid();
    }
    Query query = authorized(applicationId, formId);
    var ids = new LinkedHashSet<>(resourceIds);
    if (ids.isEmpty()) {
      return List.of();
    }
    var rows = options(query.with(" and p.resourceId in :ids", "ids", ids), 0, 101);
    if (rows.size() > ids.size()) {
      throw denied();
    }
    Map<String, FormRelationTargetOption> byId = new LinkedHashMap<>();
    for (var row : rows) {
      if (byId.putIfAbsent(row.target().accessResourceId(), row) != null) {
        throw denied();
      }
    }
    return ids.stream().filter(byId::containsKey).map(byId::get).toList();
  }

  @Override
  public FormRelationTargetMetadata describe(
      String applicationId, String formId, FormRelationTarget identity) {
    var target = authorizedTarget(applicationId, formId, identity);
    var policy = contexts.forForm(target).fieldPermissions();
    var retired = FormRelationConversion.retiredFields(target.document());
    var fields =
        target.document().fields().stream()
            .filter(
                field ->
                    field.persistent()
                        && field.type() != FormFieldType.JSON
                        && !retired.contains(field.fieldId()))
            .filter(
                field -> {
                  var access =
                      RecordPermissionModel.access(
                          policy, target.physicalModelId(), field.fieldId(), field.storageCode());
                  return !"HIDDEN".equals(access) && !"MASKED".equals(access);
                })
            .map(
                field ->
                    new FormRelationTargetMetadata.Field(
                        field.fieldId(),
                        field.label(),
                        field.labelLocalization(),
                        field.type(),
                        Set.of(FormFieldType.STRING, FormFieldType.TEXT).contains(field.type())))
            .toList();
    return new FormRelationTargetMetadata(identity, target.pageId(), target.formVersion(), fields);
  }

  @Override
  public org.simplepoint.plugin.app.form.api.model.relation.FormInverseSourceMetadata inverseSource(
      String applicationId, String formId, FormRelationTarget identity) {
    var source = authorizedTarget(applicationId, formId, identity);
    var policy = contexts.forForm(source).fieldPermissions();
    var choices =
        new java.util.ArrayList<
            org.simplepoint.plugin.app.form.api.model.relation.FormInverseSourceMetadata
                .Relation>();
    for (var relation : source.document().relations()) {
      if (relation.retired() || !applicationId.equals(relation.target().applicationId())
          || !formId.equals(relation.target().formId())) {
        continue;
      }
      if (relation.sourceField() == null
          && !FormCollectionPermissions.readable(source, relation.relationId(), policy)) {
        continue;
      }
      var resolved = targets.resolve(source, relation.relationId());
      if (!applicationId.equals(resolved.target().applicationId())
          || !formId.equals(resolved.target().formId())
          || !source.tenantId().equals(resolved.target().tenantId())) {
        throw denied();
      }
      if (relation.sourceField() == null) {
        var label = relation.collectionLabel();
        choices.add(new org.simplepoint.plugin.app.form.api.model.relation.FormInverseSourceMetadata.Relation(
            relation.relationId(), label == null ? resolved.target().pageName() : label.defaultValue(),
            label, null, null, true, true));
        continue;
      }
      var selected = FormRelationSourceField.resolve(source, relation.sourceField());
      if (!selected.readable(policy)) {
        continue;
      }
      var field = selected.field();
      var detail = selected.detail();
      choices.add(
          new org.simplepoint.plugin.app.form.api.model.relation.FormInverseSourceMetadata.Relation(
              relation.relationId(),
              field.label(),
              field.labelLocalization(),
              detail == null ? null : detail.label(),
              detail == null ? null : detail.labelLocalization(),
              !field.storageRequired()));
    }
    return new org.simplepoint.plugin.app.form.api.model.relation.FormInverseSourceMetadata(
        identity, source.formVersion(), choices);
  }

  private PublishedApplicationFormResolver.PublishedApplicationForm authorizedTarget(
      String applicationId, String formId, FormRelationTarget identity) {
    Query query = authorized(applicationId, formId);
    if (identity == null) {
      throw invalid();
    }
    query =
        query
            .with(" and p.resourceId = :resource", "resource", identity.accessResourceId())
            .with(" and p.applicationId = :application", "application", identity.applicationId())
            .with(" and p.formId = :form", "form", identity.formId());
    if (bind(em.createQuery("select count(p.id) " + query.hql(), Long.class), query)
            .getSingleResult()
        != 1) {
      throw denied();
    }
    return targets.describeTarget(identity);
  }

  private Query authorized(String applicationId, String formId) {
    String tenant = context.requireActor().tenantId();
    var authorization = AuthorizationContextHolder.getContext();
    boolean admin = Boolean.TRUE.equals(authorization.getIsAdministrator());
    var resources =
        authorization.getResources() == null
            ? Set.<String>of()
            : new LinkedHashSet<>(authorization.getResources());
    if (!admin && !resources.contains("app.workbench.view")) {
      throw denied();
    }
    forms.get(applicationId, formId); // Source ownership is mandatory even for administrators.
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("tenant", tenant);
    parameters.put("archived", ApplicationFormStatus.ARCHIVED);
    parameters.put("active", ApplicationFormPageBindingStatus.ACTIVE);
    String where = FROM;
    if (!admin) {
      // A bounded trusted scope keeps the portable prepared query below database parameter limits.
      if (resources.size() > 16384) {
        throw denied();
      }
      if (resources.isEmpty()) {
        where += " and 1 = 0";
      } else {
        where += " and p.resourceCode in :resources";
        parameters.put("resources", resources);
      }
    }
    return new Query(where, parameters);
  }

  private List<FormRelationTargetOption> options(Query query, int offset, int size) {
    return bind(
            em.createQuery(
                "select p, a.name, a.nameLocalization, f.name "
                    + query.hql()
                    + " order by a.createdAt desc, a.id desc, p.sortOrder asc, p.id asc",
                Object[].class),
            query)
        .setFirstResult(offset)
        .setMaxResults(size)
        .getResultList()
        .stream()
        .map(
            row -> {
              var page = (WorkspaceApplicationPage) row[0];
              return new FormRelationTargetOption(
                  new FormRelationTarget(
                      page.getApplicationId(), page.getFormId(), page.getResourceId()),
                  page.getId(),
                  (String) row[1],
                  page.getName(),
                  (String) row[3],
                  (LocalizedText) row[2],
                  page.getNameLocalization());
            })
        .toList();
  }

  private static <T> TypedQuery<T> bind(TypedQuery<T> query, Query specification) {
    specification.parameters().forEach(query::setParameter);
    return query;
  }

  private record Query(String hql, Map<String, Object> parameters) {
    Query with(String predicate, String key, Object value) {
      var next = new LinkedHashMap<>(parameters);
      next.put(key, value);
      return new Query(hql + predicate, next);
    }
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.DOCUMENT_INVALID, "Invalid relation catalog request");
  }

  private static ApplicationFormException denied() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
        "Relation authoring metadata is unavailable");
  }
}
