package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.platform.resource.ResourceLifecycleEvents;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.service.ApplicationAccessPort;
import org.simplepoint.plugin.app.form.api.service.ApplicationPageAccessPort;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationResourceOfflineEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** APP owns lifecycle intent; permission and data modules own their local effects. */
@Service
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
public class ApplicationResourceLifecycleService {
  private final EntityManager em;
  private final FormRequestContext context;
  private final ApplicationAccessPort applications;
  private final ApplicationPageAccessPort pages;
  private final EventPublisher events;

  /** Creates the lifecycle coordinator without depending on participant implementations. */
  public ApplicationResourceLifecycleService(EntityManager em, FormRequestContext context,
      ApplicationAccessPort applications, ApplicationPageAccessPort pages, EventPublisher events) {
    this.em = em;
    this.context = context;
    this.applications = applications;
    this.pages = pages;
    this.events = events;
  }

  /** Inspects persisted blockers and progress; a repeated inspection never starts an operation. */
  @Transactional(readOnly = true)
  public LifecycleView inspect(String applicationId, String pageId) {
    var actor = context.requireActor();
    applications.requireApplication(actor.tenantId(), applicationId);
    var bindings = em.createQuery("from ApplicationFormPageBindingEntity b where b.tenantId = :tenant "
        + "and b.applicationId = :app and b.pageId = :page "
        + "order by case when b.status in :published then 0 else 1 end, b.generation desc",
        ApplicationFormPageBindingEntity.class).setParameter("tenant", actor.tenantId())
        .setParameter("app", applicationId).setParameter("page", pageId)
        .setParameter("published", Set.of(ApplicationFormPageBindingStatus.ACTIVE,
            ApplicationFormPageBindingStatus.RETIRED)).setMaxResults(1)
        .getResultList();
    if (bindings.isEmpty()) {
      throw conflict("The page has no published resource");
    }
    var binding = bindings.getFirst();
    var operations = em.createQuery("from ApplicationResourceOfflineEntity o where o.tenantId = :tenant "
        + "and o.pageId = :page and o.generation = :generation", ApplicationResourceOfflineEntity.class)
        .setParameter("tenant", actor.tenantId()).setParameter("page", pageId)
        .setParameter("generation", binding.getGeneration()).getResultList();
    return operations.isEmpty() ? new LifecycleView(null, pageId, binding.getGeneration(),
        binding.getStatus() == ApplicationFormPageBindingStatus.ACTIVE ? "ONLINE" : "UNAVAILABLE",
        unfinished(actor.tenantId(), pageId), List.of(), List.of())
        : view(operations.getFirst(), unfinished(actor.tenantId(), pageId));
  }

  /** Fences submissions and publication before recording a transactional offline request. */
  @Transactional
  public LifecycleView offline(String applicationId, String pageId, String operationId,
                               long expectedGeneration) {
    try {
      UUID.fromString(operationId);
    } catch (RuntimeException invalid) {
      throw conflict("A UUID operationId is required");
    }
    var actor = context.requireActor();
    applications.requireApplicationForUpdate(actor.tenantId(), applicationId);
    var existing = em.find(ApplicationResourceOfflineEntity.class, operationId);
    if (existing != null) {
      if (!actor.tenantId().equals(existing.getTenantId()) || !pageId.equals(existing.getPageId())
          || !applicationId.equals(existing.getApplicationId()) || expectedGeneration != existing.getGeneration()) {
        throw conflict("Offline operation identity is already in use");
      }
      return view(existing, unfinished(actor.tenantId(), pageId));
    }
    pages.requirePageForUpdate(actor.tenantId(), applicationId, pageId);
    var bindings = em.createQuery("from ApplicationFormPageBindingEntity b where b.tenantId = :tenant "
        + "and b.applicationId = :app and b.pageId = :page and b.status = :active",
        ApplicationFormPageBindingEntity.class).setParameter("tenant", actor.tenantId())
        .setParameter("app", applicationId).setParameter("page", pageId)
        .setParameter("active", ApplicationFormPageBindingStatus.ACTIVE).getResultList();
    if (bindings.size() != 1 || bindings.getFirst().getGeneration() != expectedGeneration) {
      throw conflict("Publication changed; inspect the current resource before taking it offline");
    }
    var binding = bindings.getFirst();
    var form = em.find(ApplicationFormEntity.class, binding.getFormId(), LockModeType.PESSIMISTIC_WRITE);
    if (form == null || !actor.tenantId().equals(form.getTenantId())) {
      throw conflict("Form is unavailable");
    }
    if (unfinished(actor.tenantId(), pageId) > 0) {
      throw conflict("Unfinished submissions or workflows must be resolved before taking the resource offline");
    }
    Long publishing = em.createQuery("select count(b) from ApplicationFormPageBindingEntity b "
        + "where b.tenantId = :tenant and b.pageId = :page and b.status not in :closed", Long.class)
        .setParameter("tenant", actor.tenantId()).setParameter("page", pageId)
        .setParameter("closed", Set.of(ApplicationFormPageBindingStatus.ACTIVE,
            ApplicationFormPageBindingStatus.RETIRED, ApplicationFormPageBindingStatus.CANCELLED))
        .getSingleResult();
    if (publishing > 0) {
      throw conflict("Finish or cancel the pending publication first");
    }
    pages.offlineBinding(actor.tenantId(), applicationId, pageId, false);
    Instant now = Instant.now();
    var operation = new ApplicationResourceOfflineEntity();
    operation.setId(operationId);
    operation.setTenantId(actor.tenantId());
    operation.setApplicationId(applicationId);
    operation.setPageId(pageId);
    operation.setFormId(form.getId());
    operation.setBindingId(binding.getId());
    operation.setResourceId(binding.getResourceId());
    operation.setGeneration(expectedGeneration);
    operation.setState("OFFLINING");
    operation.setRequiredParticipants(String.join(",", ResourceLifecycleEvents.REQUIRED_PARTICIPANTS.stream().sorted().toList()));
    operation.setCompletedParticipants("");
    operation.setRequestEventId(UUID.randomUUID().toString());
    operation.setCreatedBy(actor.actorId());
    operation.setCreatedAt(now);
    operation.setUpdatedAt(now);
    em.persist(operation);
    em.flush();
    events.publish(new EventMessage(UUID.fromString(operation.getRequestEventId()),
        ResourceLifecycleEvents.OFFLINE_REQUESTED, 1, "app", actor.tenantId(), binding.getResourceId(),
        expectedGeneration, operationId, operationId, now, JsonNodeFactory.instance.objectNode()
        .put("managedOwner", "app").put("managedKey", binding.getResourceManagedKey())
        .put("resourceCode", binding.getResourceCode()).put("rootRequestEventId", operation.getRequestEventId())));
    return view(operation, 0);
  }

  /** Reissues the same business operation after a failure, with bounded and audited retry requests. */
  @Transactional
  public LifecycleView retry(String applicationId, String pageId, String operationId, String requestId) {
    try {
      UUID.fromString(requestId);
    } catch (RuntimeException invalid) {
      throw conflict("A UUID retry requestId is required");
    }
    var actor = context.requireActor();
    applications.requireApplicationForUpdate(actor.tenantId(), applicationId);
    var operation = em.find(ApplicationResourceOfflineEntity.class, operationId, LockModeType.PESSIMISTIC_WRITE);
    if (operation == null || !actor.tenantId().equals(operation.getTenantId())
        || !applicationId.equals(operation.getApplicationId()) || !pageId.equals(operation.getPageId())) {
      throw conflict("Offline operation is unavailable");
    }
    if ("OFFLINE".equals(operation.getState()) || requestId.equals(operation.getRetryRequestId())) {
      return view(operation, 0);
    }
    if (operation.getRetryAt() != null && operation.getRetryAt().plusSeconds(30).isAfter(Instant.now())) {
      throw conflict("Wait before requesting another recovery attempt");
    }
    final var binding = em.find(ApplicationFormPageBindingEntity.class, operation.getBindingId());
    operation.setRetryRequestId(requestId);
    operation.setRetryActor(actor.actorId());
    operation.setRetryAt(Instant.now());
    operation.setRetries(operation.getRetries() + 1);
    operation.setUpdatedAt(Instant.now());
    events.publish(new EventMessage(UUID.fromString(requestId), ResourceLifecycleEvents.OFFLINE_REQUESTED,
        1, "app", actor.tenantId(), operation.getResourceId(), operation.getGeneration(), operationId,
        operationId, Instant.now(), JsonNodeFactory.instance.objectNode().put("managedOwner", "app")
        .put("managedKey", binding.getResourceManagedKey()).put("resourceCode", binding.getResourceCode())
        .put("rootRequestEventId", operation.getRequestEventId())));
    return view(operation, 0);
  }

  /** Called only by explicitly trusted completion subscriptions inside the inbox transaction. */
  @Transactional
  public void completed(EventMessage event) {
    if (!ResourceLifecycleEvents.OFFLINE_COMPLETED.equals(event.eventType()) || event.schemaVersion() != 1
        || !event.producer().equals(event.payload().path("participant").asText())) {
      throw conflict("Invalid lifecycle completion");
    }
    var initial = em.find(ApplicationResourceOfflineEntity.class, event.operationId());
    if (initial == null || !event.tenantId().equals(initial.getTenantId())
        || !event.resourceId().equals(initial.getResourceId()) || event.generation() != initial.getGeneration()
        || !initial.getRequestEventId().equals(event.payload().path("requestEventId").asText())) {
      throw conflict("Completion does not match the resource operation");
    }
    if ("OFFLINE".equals(initial.getState())) {
      return;
    }
    applications.requireApplicationForUpdate(initial.getTenantId(), initial.getApplicationId());
    em.refresh(initial, LockModeType.PESSIMISTIC_WRITE);
    if ("OFFLINE".equals(initial.getState())) {
      return;
    }
    pages.offlineBinding(initial.getTenantId(), initial.getApplicationId(), initial.getPageId(), false);
    em.find(ApplicationFormEntity.class, initial.getFormId(), LockModeType.PESSIMISTIC_WRITE);
    Set<String> required = split(initial.getRequiredParticipants());
    if (!required.contains(event.producer())) {
      throw conflict("Unexpected lifecycle participant");
    }
    Set<String> completed = split(initial.getCompletedParticipants());
    completed.add(event.producer());
    initial.setCompletedParticipants(String.join(",", completed.stream().sorted().toList()));
    initial.setUpdatedAt(Instant.now());
    if (completed.containsAll(required)) {
      var binding = em.find(ApplicationFormPageBindingEntity.class, initial.getBindingId(), LockModeType.PESSIMISTIC_WRITE);
      if (binding.getGeneration() != initial.getGeneration()) {
        throw conflict("Resource publication changed during offline");
      }
      binding.setStatus(ApplicationFormPageBindingStatus.RETIRED);
      binding.setUpdatedAt(Instant.now());
      binding.setExecutionToken(null);
      binding.setLeaseUntil(null);
      pages.offlineBinding(initial.getTenantId(), initial.getApplicationId(), initial.getPageId(), true);
      initial.setState("OFFLINE");
    }
  }

  private long unfinished(String tenant, String page) {
    return em.createQuery("select count(s) from ApplicationFormSubmissionEntity s where s.tenantId = :tenant "
        + "and s.pageId = :page and s.step <> 'TERMINAL'", Long.class)
        .setParameter("tenant", tenant).setParameter("page", page).getSingleResult();
  }

  private static LifecycleView view(ApplicationResourceOfflineEntity row, long blockers) {
    return new LifecycleView(row.getId(), row.getPageId(), row.getGeneration(), row.getState(), blockers,
        split(row.getRequiredParticipants()).stream().sorted().toList(),
        split(row.getCompletedParticipants()).stream().sorted().toList());
  }

  private static Set<String> split(String value) {
    return value.isBlank() ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList(value.split(",")));
  }

  private static ApplicationFormException conflict(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.PAGE_BINDING_CONFLICT, message);
  }

  /** Safe lifecycle metadata, without credentials, event bodies or authorization snapshots. */
  public record LifecycleView(String operationId, String pageId, long generation, String state,
                              long unfinishedSubmissions, List<String> requiredParticipants,
                              List<String> completedParticipants) {}
}
