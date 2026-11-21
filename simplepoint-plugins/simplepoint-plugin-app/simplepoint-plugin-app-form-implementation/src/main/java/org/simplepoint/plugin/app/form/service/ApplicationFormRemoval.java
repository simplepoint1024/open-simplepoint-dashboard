package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.ApplicationWorkbenchResources;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDraftRevision;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.service.ApplicationAccessPort;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormRemovalEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.dna.runtime.api.event.FormStorageRemovalEvents;
import org.simplepoint.plugin.dna.runtime.api.schema.CollectionModelRelation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Permanent removal of offline form metadata, completed after the data owner confirms storage deletion. */
@Service
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
public class ApplicationFormRemoval implements EventHandler {
  private final EntityManager entities;
  private final ApplicationAccessPort applications;
  private final FormRequestContext context;
  private final FormDocumentCodec documents;
  private final ApplicationFormService forms;
  private final EventPublisher events;

  public ApplicationFormRemoval(EntityManager entities, ApplicationAccessPort applications,
      FormRequestContext context, FormDocumentCodec documents, ApplicationFormService forms, EventPublisher events) {
    this.entities = entities;
    this.applications = applications;
    this.context = context;
    this.documents = documents;
    this.forms = forms;
    this.events = events;
  }

  public record Progress(String id, String formId, int version, boolean completed) {}
  public record Selection(String formId, int version, UUID id, Long draftRevision) {}

  @Transactional
  public List<Progress> requestBatch(String applicationId, List<Selection> selections) {
    ApplicationWorkbenchAccess.require(ApplicationWorkbenchResources.DELETE_FORM);
    return requestOwnedForms(applicationId, selections);
  }

  /** Used by the owner of an application removal to clear its own contained forms. */
  @Transactional
  public List<Progress> requestApplicationForms(String applicationId, List<Selection> selections) {
    ApplicationWorkbenchAccess.require(ApplicationWorkbenchResources.DELETE_APPLICATION);
    return requestOwnedForms(applicationId, selections);
  }

  private List<Progress> requestOwnedForms(String applicationId, List<Selection> selections) {
    if (selections == null || selections.isEmpty() || selections.size() > FormStorageRemovalEvents.MAX_FORMS
        || selections.stream().anyMatch(row -> row == null || row.formId() == null || row.id() == null
        || row.version() < 0 || (row.version() == 0) != (row.draftRevision() != null))
        || selections.stream().map(Selection::formId).distinct().count() != selections.size()
        || selections.stream().map(Selection::id).distinct().count() != selections.size()) throw blocked();
    String tenant = context.requireActor().tenantId();
    applications.requireApplicationForUpdate(tenant, applicationId);
    var drafts = new ArrayList<ApplicationFormDraftRevision>();
    var storage = new ArrayList<FormStorageRemovalEvents.Member>();
    var result = new ArrayList<Progress>();
    var now = Instant.now();
    for (var row : selections.stream().sorted(Comparator.comparing(Selection::formId)).toList()) {
      var prior = entities.find(ApplicationFormRemovalEntity.class, row.id().toString());
      if (prior != null) {
        if (!tenant.equals(prior.getTenantId()) || !applicationId.equals(prior.getApplicationId())
            || !row.formId().equals(prior.getFormId()) || row.version() != prior.getFormVersion()
            || !java.util.Objects.equals(row.draftRevision(), prior.getDraftRevision())) throw blocked();
        result.add(progress(prior));
        continue;
      }
      var operation = new ApplicationFormRemovalEntity();
      operation.setId(row.id().toString());
      operation.setTenantId(tenant);
      operation.setApplicationId(applicationId);
      operation.setFormId(row.formId());
      operation.setFormVersion(row.version());
      operation.setDraftRevision(row.draftRevision());
      operation.setRequestedAt(now);
      if (row.draftRevision() != null) {
        drafts.add(new ApplicationFormDraftRevision(row.formId(), row.draftRevision()));
        operation.setCompletedAt(now);
      } else {
        var form = lockForm(tenant, applicationId, row.formId());
        if (row.version() != form.getLatestVersion() || form.getPhysicalModelId() == null
            || form.getStatus() == ApplicationFormStatus.ARCHIVED) throw blocked();
        requireOffline(tenant, row.formId());
        storage.add(new FormStorageRemovalEvents.Member(row.formId(), row.version(), row.id(),
            form.getPhysicalModelId(), physicalForms(tenant, row.formId())));
        form.setStatus(ApplicationFormStatus.ARCHIVED);
      }
      entities.persist(operation);
      result.add(progress(operation));
    }
    if (!drafts.isEmpty()) forms.deleteDrafts(applicationId, drafts);
    if (!storage.isEmpty()) events.publish(FormStorageRemovalEvents.requested(tenant, applicationId, storage));
    return List.copyOf(result);
  }

  @Transactional(readOnly = true)
  public List<Progress> progressBatch(String applicationId, List<String> formIds) {
    ApplicationWorkbenchAccess.require(ApplicationWorkbenchResources.DELETE_FORM);
    if (formIds == null || formIds.isEmpty() || formIds.size() > 100
        || formIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 36)) throw blocked();
    var actor = context.requireActor();
    applications.requireApplication(actor.tenantId(), applicationId);
    return entities.createQuery("from ApplicationFormRemovalEntity r where r.tenantId = :tenant"
            + " and r.applicationId = :app and r.formId in :ids order by r.formId", ApplicationFormRemovalEntity.class)
        .setParameter("tenant", actor.tenantId()).setParameter("app", applicationId).setParameter("ids", formIds)
        .setMaxResults(100).getResultList().stream().map(ApplicationFormRemoval::progress).toList();
  }

  @Override
  public EventSubscription subscription() {
    return FormStorageRemovalEvents.APPLICATION;
  }

  @Override
  @Transactional
  public void handle(EventMessage event) {
    FormStorageRemovalEvents.requireAcknowledgement(event);
    var initial = entities.find(ApplicationFormRemovalEntity.class, event.operationId());
    if (initial == null || !event.tenantId().equals(initial.getTenantId())
        || !event.resourceId().equals(initial.getFormId()) || event.generation() != initial.getFormVersion()) {
      throw blocked();
    }
    if (initial.getCompletedAt() != null) return;
    String tenant = initial.getTenantId();
    String formId = initial.getFormId();
    applications.requireApplicationForUpdate(tenant, initial.getApplicationId());
    entities.refresh(initial, LockModeType.PESSIMISTIC_WRITE);
    if (initial.getCompletedAt() != null) return;
    var form = lockForm(tenant, initial.getApplicationId(), formId);
    if (form.getStatus() != ApplicationFormStatus.ARCHIVED) throw blocked();
    requireOffline(tenant, formId);
    preservePageGenerations(tenant, formId);
    mutate("delete from ApplicationFormReleaseKeyEntity k where k.tenantId = :tenant and k.bindingId in"
        + " (select b.id from ApplicationFormPageBindingEntity b where b.tenantId = :tenant and b.formId = :form)",
        tenant, formId);
    for (String entity : List.of("ApplicationFormWritePolicyPublicationEntity", "ApplicationResourceOfflineEntity",
        "ApplicationFormPageBindingEntity", "ApplicationFormPublicationEntity", "ApplicationFormSubmissionEntity",
        "ApplicationFormVersionEntity", "ApplicationFormDraftEntity")) {
      mutate("delete from " + entity + " r where r.tenantId = :tenant and r.formId = :form", tenant, formId);
    }
    entities.createQuery("update WorkspaceApplicationPage p set p.formId = null, p.formVersion = null,"
            + " p.resourceId = null, p.resourceCode = null, p.status = 'DRAFT', p.updatedAt = :now"
            + " where p.tenantId = :tenant and p.applicationId = :app and p.formId = :form and p.status = 'OFFLINE'")
        .setParameter("tenant", tenant).setParameter("app", initial.getApplicationId())
        .setParameter("form", formId).setParameter("now", Instant.now()).executeUpdate();
    entities.remove(form);
    initial.setCompletedAt(Instant.now());
  }

  private void preservePageGenerations(String tenant, String form) {
    var generations = entities.createQuery("select b.pageId as pageId, max(b.generation) as generation"
            + " from ApplicationFormPageBindingEntity b where b.tenantId = :tenant and b.formId = :form group by b.pageId",
            jakarta.persistence.Tuple.class)
        .setParameter("tenant", tenant).setParameter("form", form).getResultList();
    for (var row : generations) {
      entities.createQuery("update WorkspaceApplicationPage p set p.publicationGeneration = :generation"
              + " where p.tenantId = :tenant and p.id = :page and p.publicationGeneration < :generation")
          .setParameter("tenant", tenant).setParameter("page", row.get("pageId", String.class))
          .setParameter("generation", row.get("generation", Long.class)).executeUpdate();
    }
  }

  private void requireOffline(String tenant, String formId) {
    boolean binding = exists("select b.id from ApplicationFormPageBindingEntity b where b.tenantId = :tenant"
        + " and b.formId = :form and b.status not in :closed", tenant, formId, Map.of("closed",
        Set.of(ApplicationFormPageBindingStatus.RETIRED, ApplicationFormPageBindingStatus.CANCELLED)));
    boolean publication = exists("select p.id from ApplicationFormPublicationEntity p where p.tenantId = :tenant"
        + " and p.formId = :form and p.status not in :closed", tenant, formId, Map.of("closed",
        Set.of(ApplicationFormPublicationStatus.ACTIVE, ApplicationFormPublicationStatus.RETIRED,
            ApplicationFormPublicationStatus.FAILED)));
    boolean policy = exists("select p.id from ApplicationFormWritePolicyPublicationEntity p where p.tenantId = :tenant"
        + " and p.formId = :form and p.status not in :closed", tenant, formId, Map.of("closed",
        Set.of(FormWritePolicyPublicationStatus.ACTIVE, FormWritePolicyPublicationStatus.CANCELLED)));
    boolean submission = exists("select s.id from ApplicationFormSubmissionEntity s where s.tenantId = :tenant"
        + " and s.formId = :form and (s.step <> 'TERMINAL' or s.processInstanceId is not null)",
        tenant, formId, Map.of());
    if (binding || publication || policy || submission) throw blocked();
  }

  private Set<String> physicalForms(String tenant, String formId) {
    var result = new TreeSet<String>();
    result.add(formId);
    var versions = entities.createQuery("from ApplicationFormVersionEntity v where v.tenantId = :tenant"
            + " and v.formId = :form order by v.versionNumber", ApplicationFormVersionEntity.class)
        .setParameter("tenant", tenant).setParameter("form", formId).getResultStream();
    try (versions) {
      versions.forEach(version -> {
        var document = documents.decode(version.getDocumentJson());
        for (var detail : document.details()) {
          result.add(UUID.nameUUIDFromBytes((formId + ":detail:" + detail.detailId())
              .getBytes(StandardCharsets.UTF_8)).toString());
        }
        for (var relation : document.relations()) {
          if (relation.sourceField() == null) {
            result.add(new CollectionModelRelation(relation.relationId(), "source", "target").modelFormId(formId));
          }
        }
        if (result.size() > FormStorageRemovalEvents.MAX_MODELS) throw blocked();
      });
    }
    return result;
  }

  private boolean exists(String hql, String tenant, String form, Map<String, ?> parameters) {
    var query = entities.createQuery(hql, String.class).setParameter("tenant", tenant).setParameter("form", form);
    parameters.forEach(query::setParameter);
    return !query.setMaxResults(1).getResultList().isEmpty();
  }

  private void mutate(String hql, String tenant, String form) {
    entities.createQuery(hql).setParameter("tenant", tenant).setParameter("form", form).executeUpdate();
  }

  private ApplicationFormEntity lockForm(String tenant, String applicationId, String formId) {
    var form = entities.find(ApplicationFormEntity.class, formId, LockModeType.PESSIMISTIC_WRITE);
    if (form == null || !tenant.equals(form.getTenantId()) || !applicationId.equals(form.getApplicationId())) {
      throw new ApplicationFormException(ApplicationFormErrorCode.FORM_NOT_FOUND, "Form is unavailable");
    }
    return form;
  }

  private static Progress progress(ApplicationFormRemovalEntity row) {
    return new Progress(row.getId(), row.getFormId(), row.getFormVersion(), row.getCompletedAt() != null);
  }

  private static ApplicationFormException blocked() {
    return new ApplicationFormException(ApplicationFormErrorCode.FORM_DELETE_BLOCKED,
        "Form removal requires fully offline bindings, resolved approvals and no pending publication");
  }
}
