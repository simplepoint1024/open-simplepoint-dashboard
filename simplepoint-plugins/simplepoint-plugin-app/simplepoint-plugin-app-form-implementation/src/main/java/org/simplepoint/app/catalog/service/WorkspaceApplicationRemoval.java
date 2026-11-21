package org.simplepoint.app.catalog.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.platform.resource.ApplicationCatalogRemovalEvents;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDraftRevision;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplication;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplicationPage;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplicationRemovalEntity;
import org.simplepoint.plugin.app.form.service.ApplicationFormRemoval;
import org.simplepoint.plugin.app.form.service.FormRequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Removes an offline application after its forms and authorization catalog have been removed. */
@Service
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
public class WorkspaceApplicationRemoval implements EventHandler {
  private final EntityManager entities;
  private final FormRequestContext context;
  private final ApplicationFormService forms;
  private final ApplicationFormRemoval formRemoval;
  private final EventPublisher events;
  private final TransactionTemplate transaction;

  public WorkspaceApplicationRemoval(EntityManager entities, FormRequestContext context,
      ApplicationFormService forms, ApplicationFormRemoval formRemoval, EventPublisher events,
      PlatformTransactionManager manager) {
    this.entities = entities;
    this.context = context;
    this.forms = forms;
    this.formRemoval = formRemoval;
    this.events = events;
    this.transaction = new TransactionTemplate(manager);
  }

  public record Selection(String applicationId, Instant expectedUpdatedAt, UUID id) {}
  public record Progress(String applicationId, String id, String phase) {}

  @Transactional
  public List<Progress> request(List<Selection> selection) {
    requireAuthority();
    if (selection == null || selection.isEmpty() || selection.size() > 100
        || selection.stream().anyMatch(row -> row == null || row.id() == null
        || row.expectedUpdatedAt() == null || row.applicationId() == null)
        || selection.stream().map(Selection::applicationId).distinct().count() != selection.size()
        || selection.stream().map(Selection::id).distinct().count() != selection.size()) throw blocked();
    String tenant = context.requireActor().tenantId();
    return selection.stream().sorted(Comparator.comparing(Selection::applicationId))
        .map(row -> requestOne(tenant, row)).toList();
  }

  private Progress requestOne(String tenant, Selection input) {
    var receipt = entities.find(WorkspaceApplicationRemovalEntity.class, input.id().toString());
    if (receipt != null) return replay(tenant, input, receipt);
    var app = lockApplication(tenant, input.applicationId());
    receipt = entities.find(WorkspaceApplicationRemovalEntity.class, input.id().toString());
    if (receipt != null) return replay(tenant, input, receipt);
    if (!Set.of("DRAFT", "OFFLINE").contains(app.getStatus())
        || app.getNavigationPending() != null || !app.getUpdatedAt().equals(input.expectedUpdatedAt())) throw blocked();
    var pages = pages(app);
    if (pages.isEmpty() || pages.size() > 500
        || pages.stream().anyMatch(page -> !Set.of("DRAFT", "OFFLINE").contains(page.getStatus()))) throw blocked();

    var existingForms = entities.createQuery("select f.id as id, f.status as status,"
            + " f.draftRevision as revision, f.latestVersion as version, f.physicalModelId as model"
            + " from ApplicationFormEntity f where f.tenantId = :tenant and f.applicationId = :app order by f.id",
            Tuple.class).setParameter("tenant", tenant).setParameter("app", app.getId()).getResultList();
    var drafts = new ArrayList<ApplicationFormDraftRevision>();
    var published = new ArrayList<ApplicationFormRemoval.Selection>();
    for (var entry : existingForms) {
      String form = entry.get("id", String.class);
      var status = entry.get("status", ApplicationFormStatus.class);
      int version = entry.get("version", Integer.class);
      if (status == ApplicationFormStatus.DRAFT && version == 0 && entry.get("model") == null) {
        drafts.add(new ApplicationFormDraftRevision(form, entry.get("revision", Long.class)));
      } else if (status == ApplicationFormStatus.ARCHIVED) {
        if (!hasPendingFormRemoval(tenant, app.getId(), form)) throw blocked();
      } else {
        UUID operation = UUID.nameUUIDFromBytes((input.id() + ":" + form).getBytes(StandardCharsets.UTF_8));
        published.add(new ApplicationFormRemoval.Selection(form, version, operation, null));
      }
    }
    if (!drafts.isEmpty()) forms.deleteDrafts(app.getId(), drafts);
    if (!published.isEmpty()) formRemoval.requestBatch(app.getId(), published);

    var row = new WorkspaceApplicationRemovalEntity();
    row.setId(input.id().toString());
    row.setTenantId(tenant);
    row.setApplicationId(app.getId());
    row.setApplicationUpdatedAt(input.expectedUpdatedAt());
    row.setNavigationRevision(app.getNavigationRevision());
    row.setRequestedAt(Instant.now());
    entities.persist(row);
    app.setStatus("DELETING");
    app.setUpdatedAt(Instant.now());
    if (!hasForms(tenant, app.getId())) requestCatalog(row, pages);
    return progress(row);
  }

  @Transactional(readOnly = true)
  public List<Progress> progress(List<String> applications) {
    requireAuthority();
    if (applications == null || applications.isEmpty() || applications.size() > 100
        || applications.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 36)) throw blocked();
    return entities.createQuery("from WorkspaceApplicationRemovalEntity r where r.tenantId = :tenant"
            + " and r.applicationId in :apps order by r.applicationId", WorkspaceApplicationRemovalEntity.class)
        .setParameter("tenant", context.requireActor().tenantId()).setParameter("apps", applications)
        .setMaxResults(100).getResultList().stream().map(WorkspaceApplicationRemoval::progress).toList();
  }

  @Scheduled(fixedDelayString = "${simplepoint.app.application-removal-delay:5s}")
  public void advance() {
    List<String> ready = transaction.execute(status -> entities.createQuery(
        "select r.id from WorkspaceApplicationRemovalEntity r where r.catalogRequestedAt is null"
            + " and not exists (select f.id from ApplicationFormEntity f where f.tenantId = r.tenantId"
            + " and f.applicationId = r.applicationId) order by r.requestedAt, r.id", String.class)
        .setMaxResults(25).getResultList());
    if (ready == null) return;
    for (String id : ready) {
      transaction.executeWithoutResult(status -> {
        var initial = entities.find(WorkspaceApplicationRemovalEntity.class, id);
        if (initial == null || initial.getCatalogRequestedAt() != null) return;
        var app = lockApplication(initial.getTenantId(), initial.getApplicationId());
        entities.refresh(initial, LockModeType.PESSIMISTIC_WRITE);
        if (initial.getCatalogRequestedAt() == null && !hasForms(initial.getTenantId(), app.getId())) {
          requestCatalog(initial, pages(app));
        }
      });
    }
  }

  private void requestCatalog(WorkspaceApplicationRemovalEntity row, List<WorkspaceApplicationPage> pages) {
    row.setCatalogRequestedAt(Instant.now());
    var ids = new TreeSet<String>();
    pages.forEach(page -> ids.add(page.getId()));
    events.publish(ApplicationCatalogRemovalEvents.requested(row.getTenantId(), row.getApplicationId(),
        row.getNavigationRevision(), UUID.fromString(row.getId()), ids));
  }

  @Override
  public EventSubscription subscription() {
    return ApplicationCatalogRemovalEvents.APPLICATION;
  }

  @Override
  @Transactional
  public void handle(EventMessage event) {
    ApplicationCatalogRemovalEvents.requireCompletion(event);
    var row = entities.find(WorkspaceApplicationRemovalEntity.class, event.operationId());
    if (row == null || !event.tenantId().equals(row.getTenantId())
        || !event.resourceId().equals(row.getApplicationId()) || event.generation() != row.getNavigationRevision()
        || row.getCatalogRequestedAt() == null) throw blocked();
    if (row.getCompletedAt() != null) return;
    var app = lockApplication(row.getTenantId(), row.getApplicationId());
    entities.refresh(row, LockModeType.PESSIMISTIC_WRITE);
    if (row.getCompletedAt() != null) return;
    if (!"DELETING".equals(app.getStatus()) || app.getNavigationRevision() != row.getNavigationRevision()
        || hasForms(row.getTenantId(), app.getId())) throw blocked();
    var pages = pages(app);
    if (pages.stream().anyMatch(page -> page.getFormId() != null
        || !Set.of("DRAFT", "OFFLINE").contains(page.getStatus()))) throw blocked();
    pages.forEach(entities::remove);
    entities.remove(app);
    row.setCompletedAt(Instant.now());
  }

  private List<WorkspaceApplicationPage> pages(WorkspaceApplication app) {
    return entities.createQuery("from WorkspaceApplicationPage p where p.tenantId = :tenant"
            + " and p.applicationId = :app order by p.id", WorkspaceApplicationPage.class)
        .setParameter("tenant", app.getTenantId()).setParameter("app", app.getId())
        .setMaxResults(501).getResultList();
  }

  private WorkspaceApplication lockApplication(String tenant, String id) {
    var app = entities.find(WorkspaceApplication.class, id, LockModeType.PESSIMISTIC_WRITE);
    if (app == null || !tenant.equals(app.getTenantId())) {
      throw new ApplicationFormException(ApplicationFormErrorCode.APPLICATION_NOT_FOUND, "Application unavailable");
    }
    return app;
  }

  private boolean hasForms(String tenant, String application) {
    return !entities.createQuery("select f.id from ApplicationFormEntity f where f.tenantId = :tenant"
            + " and f.applicationId = :app", String.class).setParameter("tenant", tenant)
        .setParameter("app", application).setMaxResults(1).getResultList().isEmpty();
  }

  private boolean hasPendingFormRemoval(String tenant, String application, String form) {
    return !entities.createQuery("select r.id from ApplicationFormRemovalEntity r where r.tenantId = :tenant"
            + " and r.applicationId = :app and r.formId = :form and r.completedAt is null", String.class)
        .setParameter("tenant", tenant).setParameter("app", application).setParameter("form", form)
        .setMaxResults(1).getResultList().isEmpty();
  }

  private static Progress replay(String tenant, Selection request, WorkspaceApplicationRemovalEntity existing) {
    if (!tenant.equals(existing.getTenantId()) || !request.applicationId().equals(existing.getApplicationId())
        || !request.expectedUpdatedAt().equals(existing.getApplicationUpdatedAt())) throw blocked();
    return progress(existing);
  }

  private static Progress progress(WorkspaceApplicationRemovalEntity row) {
    return new Progress(row.getApplicationId(), row.getId(), row.getCompletedAt() != null ? "COMPLETED"
        : row.getCatalogRequestedAt() != null ? "AUTHORIZATION" : "STORAGE");
  }

  private static void requireAuthority() {
    var authorization = AuthorizationContextHolder.getContext();
    if (authorization == null || !Boolean.TRUE.equals(authorization.getIsAdministrator())
        && (authorization.getResources() == null || !authorization.getResources().contains("app.workbench.apps.delete"))) {
      throw new AccessDeniedException("Application removal is forbidden");
    }
  }

  private static ApplicationFormException blocked() {
    return new ApplicationFormException(ApplicationFormErrorCode.APPLICATION_DELETE_BLOCKED,
        "Application removal requires an exact fully offline catalog");
  }
}
