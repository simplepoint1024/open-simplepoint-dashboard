package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashSet;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.service.ApplicationPageAccessPort;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormWritePolicyPublicationEntity;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyCancellationView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short, durable APP transactions; no remote call runs while control-plane locks are held. */
@Service
public class FormWritePolicyPublicationStateService {

  private final EntityManager entityManager;
  private final ApplicationPageAccessPort pages;
  private final FormRequestContext context;
  private final ObjectMapper mapper;
  private final Clock clock;

  /** Uses the existing catalog/page locking order and immutable form version metadata. */
  public FormWritePolicyPublicationStateService(final EntityManager entityManager, final ApplicationPageAccessPort pages,
      final FormRequestContext context, final ObjectMapper mapper, final Clock clock) {
    this.entityManager = entityManager;
    this.pages = pages;
    this.context = context;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Freezes service-compiled checks exactly once; retries return the original persisted intent. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PreparedPolicy prepare(final BindingWork work, final FormWritePolicyImpact impact) {
    final LockedPublication locked = lock(work);
    ApplicationFormWritePolicyPublicationEntity existing = find(work.tenantId(), work.bindingId());
    if (existing != null && (existing.getStatus() == FormWritePolicyPublicationStatus.ACTIVE
        || existing.getStatus() == FormWritePolicyPublicationStatus.CANCELLED)) {
      return view(existing);
    }
    requireAttempt(locked.binding(), work);
    FormWritePolicyPublicationGuard.requireAvailable(entityManager, work.tenantId(), work.formId(), work.pageId(), work.bindingId());
    if (existing != null) {
      return view(existing);
    }
    if (work.policyProtocolVersion() != 1 || !entityManager.createQuery(
        "select b.id from ApplicationFormPageBindingEntity b where b.tenantId = :tenant and b.formId = :form "
            + "and b.policyProtocolVersion = 0 and b.attempt > 0 and b.status not in (:terminal)", String.class)
        .setParameter("tenant", work.tenantId()).setParameter("form", work.formId())
        .setParameter("terminal", java.util.List.of(ApplicationFormPageBindingStatus.ACTIVE,
            ApplicationFormPageBindingStatus.RETIRED, ApplicationFormPageBindingStatus.CANCELLED))
        .setMaxResults(1).getResultList().isEmpty()) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS, "Complete the legacy resource publication before preparing a new policy");
    }
    var form = locked.form();
    int governing = Math.max(work.formVersion(), form.getPublishedVersion() == null ? 0 : form.getPublishedVersion());
    var activeBindings = entityManager.createQuery("select b.id from ApplicationFormPageBindingEntity b "
            + "where b.tenantId = :tenant and b.formId = :form and b.status = :active", String.class)
        .setParameter("tenant", work.tenantId()).setParameter("form", work.formId())
        .setParameter("active", ApplicationFormPageBindingStatus.ACTIVE).getResultList();
    if (impact == null || impact.expectedVersion() != form.getWritePolicyVersion() || impact.governingVersion() != governing
        || !impact.activeBindingIds().equals(new HashSet<>(activeBindings))) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS, "The active form policy or page set changed during impact analysis");
    }
    ApplicationFormVersionEntity version = entityManager.createQuery(
            "from ApplicationFormVersionEntity v where v.tenantId = :tenant and v.formId = :form "
                + "and v.versionNumber = :version", ApplicationFormVersionEntity.class)
        .setParameter("tenant", work.tenantId()).setParameter("form", work.formId()).setParameter("version", governing)
        .getResultStream().findFirst().orElseThrow(() -> failure(ApplicationFormErrorCode.VERSION_NOT_FOUND,
            "The governing immutable form version is unavailable"));
    if (version.getPhysicalModelVersion() == null) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS, "The governing schema is not ready");
    }
    long target = Math.addExact(form.getWritePolicyVersion(), 1);
    var command = new RecordWritePolicyPublicationCommand(work.physicalModelId(), work.applicationId(), work.formId(),
        new RecordSchemaReference(version.getPhysicalModelVersion(), version.getStorageChecksum()),
        form.getWritePolicyVersion(), target, "app-policy:" + work.bindingId(), version.getChecksum(), impact.checks(), 30_000, impact.valueChecks());
    var intent = new ApplicationFormWritePolicyPublicationEntity();
    intent.setId(UUID.randomUUID().toString());
    intent.setTenantId(work.tenantId());
    intent.setApplicationId(work.applicationId());
    intent.setFormId(work.formId());
    intent.setPageId(work.pageId());
    intent.setBindingId(work.bindingId());
    intent.setGoverningVersion(governing);
    intent.setExpectedVersion(command.expectedVersion());
    intent.setTargetVersion(command.targetVersion());
    intent.setCommandJson(encode(command));
    intent.setStatus(FormWritePolicyPublicationStatus.PREPARED);
    intent.setCreatedBy(context.requireActor().actorId());
    intent.setCreatedAt(clock.instant());
    intent.setUpdatedAt(clock.instant());
    entityManager.persist(intent);
    entityManager.flush();
    return view(intent);
  }

  /** Records confirmed target facts without allowing a late blocked response to undo success. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PreparedPolicy recordResult(final BindingWork work, final RecordWritePolicyPublicationView result) {
    final LockedPublication locked = lock(work);
    var intent = requireIntent(work);
    if (intent.getStatus() == FormWritePolicyPublicationStatus.CANCELLED) {
      return view(intent); // A late inspection response cannot reopen a target-confirmed cancellation.
    }
    if (result == null || result.version() != (result.published() ? intent.getTargetVersion() : intent.getExpectedVersion())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_FAILED, "The target policy result does not match the persisted intent");
    }
    String knownFingerprint = intent.getTargetFingerprint() != null ? intent.getTargetFingerprint()
        : intent.getInspectionJson() == null ? null
        : decode(intent.getInspectionJson(), RecordWritePolicyPublicationView.class).publicationFingerprint();
    if (knownFingerprint != null && !knownFingerprint.equals(result.publicationFingerprint())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IDEMPOTENCY_CONFLICT, "The target publication fingerprint changed");
    }
    if (intent.getStatus() == FormWritePolicyPublicationStatus.ACTIVE) {
      return view(intent);
    }
    requireAttempt(locked.binding(), work);
    if (result.published()) {
      intent.setStatus(FormWritePolicyPublicationStatus.TARGET_READY);
      intent.setTargetFingerprint(result.publicationFingerprint());
      intent.setInspectionJson(null);
    } else if (intent.getStatus() == FormWritePolicyPublicationStatus.PREPARED) {
      intent.setInspectionJson(encode(result));
    }
    intent.setUpdatedAt(clock.instant());
    entityManager.flush();
    return view(intent);
  }

  /** Persists the cancellation decision before any remote call; unknown outcomes keep the publication protected. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PreparedPolicy requestCancellation(final BindingWork work) {
    return requestCancellationWithinAttempt(work);
  }

  /** Joins cancellation claim so the new execution token and durable decision commit together. */
  @Transactional(propagation = Propagation.MANDATORY)
  public PreparedPolicy requestCancellationWithinAttempt(final BindingWork work) {
    final LockedPublication locked = lock(work);
    var intent = requireIntent(work);
    if (intent.getStatus() == FormWritePolicyPublicationStatus.CANCELLED
        || intent.getStatus() == FormWritePolicyPublicationStatus.ACTIVE
        || intent.getStatus() == FormWritePolicyPublicationStatus.TARGET_READY) {
      return view(intent);
    }
    requireAttempt(locked.binding(), work);
    FormWritePolicyPublicationGuard.requireAvailable(entityManager, work.tenantId(), work.formId(), work.pageId(), work.bindingId());
    intent.setStatus(FormWritePolicyPublicationStatus.CANCELLING);
    intent.setUpdatedAt(clock.instant());
    entityManager.flush();
    return view(intent);
  }

  /** Recovers the exact stored decision without constructing new impact requirements. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PreparedPolicy current(final BindingWork work) {
    var locked = lock(work);
    requireAttempt(locked.binding(), work);
    var intent = find(work.tenantId(), work.bindingId());
    return intent == null ? null : view(intent);
  }

  /** Commits the target fact, admission version and release terminal state together, preserving the old active page. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PreparedPolicy recordCancellation(final BindingWork work, final RecordWritePolicyCancellationView result) {
    final LockedPublication locked = lock(work);
    var intent = requireIntent(work);
    if (intent.getStatus() == FormWritePolicyPublicationStatus.CANCELLED) {
      return view(intent);
    }
    if (result == null || result.version() != intent.getTargetVersion()) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_FAILED, "The cancellation result does not match the target version");
    }
    String known = intent.getTargetFingerprint() != null ? intent.getTargetFingerprint()
        : intent.getInspectionJson() == null ? null
        : decode(intent.getInspectionJson(), RecordWritePolicyPublicationView.class).publicationFingerprint();
    if (known != null && !known.equals(result.publicationFingerprint())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IDEMPOTENCY_CONFLICT, "The cancelled publication identity changed");
    }
    if (intent.getStatus() == FormWritePolicyPublicationStatus.ACTIVE
        || intent.getStatus() == FormWritePolicyPublicationStatus.TARGET_READY) {
      if (result.cancelled()) {
        throw failure(ApplicationFormErrorCode.PUBLICATION_FAILED, "A confirmed publication cannot be replaced by cancellation");
      }
      return view(intent);
    }
    requireAttempt(locked.binding(), work);
    if (intent.getStatus() != FormWritePolicyPublicationStatus.CANCELLING) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS, "Cancellation must be requested before recording its result");
    }
    if (locked.form().getWritePolicyVersion() != intent.getExpectedVersion()) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS, "The form admission version changed during cancellation");
    }
    intent.setTargetFingerprint(result.targetFingerprint());
    intent.setInspectionJson(null);
    intent.setUpdatedAt(clock.instant());
    if (result.cancelled()) {
      intent.setCancellationJson(encode(result));
      intent.setStatus(FormWritePolicyPublicationStatus.CANCELLED);
      locked.form().setWritePolicyVersion(intent.getTargetVersion());
      locked.form().setUpdatedAt(clock.instant());
      locked.binding().setStatus(ApplicationFormPageBindingStatus.CANCELLED);
      locked.binding().setExecutionToken(null);
      locked.binding().setLeaseUntil(null);
      locked.binding().setErrorCode(null);
      locked.binding().setUpdatedAt(clock.instant());
    } else {
      intent.setStatus(FormWritePolicyPublicationStatus.TARGET_READY);
    }
    entityManager.flush();
    return view(intent);
  }

  /** Must join page activation so governing rules, the page and admission change atomically in APP. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void activate(final BindingWork work) {
    var actor = context.requireActor();
    if (work == null || !actor.tenantId().equals(work.tenantId())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_NOT_FOUND, "The publication was not found in the active tenant");
    }
    entityManager.flush(); // Preserve page/rule changes made earlier in the same activation transaction.
    // Baseline releases remain compatible while impact compilation is being integrated.
    // A governed form cannot return to that path or activate without a target acknowledgement.
    if (find(work.tenantId(), work.bindingId()) == null) {
      if (lock(work).form().getWritePolicyVersion() != 0) {
        throw failure(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS, "The form policy publication has not been prepared");
      }
      return;
    }
    final LockedPublication locked = lock(work);
    var intent = requireIntent(work);
    if (intent.getStatus() == FormWritePolicyPublicationStatus.ACTIVE) {
      return;
    }
    FormWritePolicyPublicationGuard.requireAvailable(entityManager, work.tenantId(), work.formId(), work.pageId(), work.bindingId());
    if (intent.getStatus() != FormWritePolicyPublicationStatus.TARGET_READY
        || locked.binding().getStatus() != ApplicationFormPageBindingStatus.ACTIVE
        || locked.form().getPublishedVersion() == null
        || locked.form().getPublishedVersion() != intent.getGoverningVersion()
        || locked.form().getWritePolicyVersion() != intent.getExpectedVersion()) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS,
          "Target policy and page activation must be confirmed before the write version changes");
    }
    locked.form().setWritePolicyVersion(intent.getTargetVersion());
    locked.form().setUpdatedAt(clock.instant());
    intent.setStatus(FormWritePolicyPublicationStatus.ACTIVE);
    intent.setUpdatedAt(clock.instant());
    entityManager.flush();
  }

  private LockedPublication lock(final BindingWork work) {
    var actor = context.requireActor();
    if (work == null || !actor.tenantId().equals(work.tenantId())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_NOT_FOUND, "The publication was not found in the active tenant");
    }
    pages.requirePageForUpdate(work.tenantId(), work.applicationId(), work.pageId());
    var form = entityManager.createQuery("from ApplicationFormEntity f where f.tenantId = :tenant "
            + "and f.applicationId = :app and f.id = :form", ApplicationFormEntity.class)
        .setParameter("tenant", work.tenantId()).setParameter("app", work.applicationId()).setParameter("form", work.formId())
        .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst()
        .orElseThrow(() -> failure(ApplicationFormErrorCode.FORM_NOT_FOUND, "The owning form is unavailable"));
    entityManager.refresh(form, LockModeType.PESSIMISTIC_WRITE);
    var binding = entityManager.find(ApplicationFormPageBindingEntity.class, work.bindingId(), LockModeType.PESSIMISTIC_WRITE);
    if (binding == null) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_NOT_FOUND, "The page publication is unavailable");
    }
    entityManager.refresh(binding, LockModeType.PESSIMISTIC_WRITE);
    if (!work.tenantId().equals(binding.getTenantId()) || !work.applicationId().equals(binding.getApplicationId())
        || !work.formId().equals(binding.getFormId()) || !work.pageId().equals(binding.getPageId())
        || work.formVersion() != binding.getFormVersion() || work.generation() != binding.getGeneration()
        || !work.physicalModelId().equals(form.getPhysicalModelId())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IDEMPOTENCY_CONFLICT, "The publication identity has changed");
    }
    return new LockedPublication(form, binding);
  }

  private ApplicationFormWritePolicyPublicationEntity find(final String tenant, final String binding) {
    return entityManager.createQuery("from ApplicationFormWritePolicyPublicationEntity p "
            + "where p.tenantId = :tenant and p.bindingId = :binding", ApplicationFormWritePolicyPublicationEntity.class)
        .setParameter("tenant", tenant).setParameter("binding", binding).getResultStream().findFirst().orElse(null);
  }

  private ApplicationFormWritePolicyPublicationEntity requireIntent(final BindingWork work) {
    var intent = find(work.tenantId(), work.bindingId());
    if (intent == null) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_NOT_FOUND, "The policy publication intent is unavailable");
    }
    return intent;
  }

  private static void requireAttempt(final ApplicationFormPageBindingEntity binding, final BindingWork work) {
    if (binding.getStatus() != ApplicationFormPageBindingStatus.ACTIVATING || work.executionToken() == null
        || !work.executionToken().equals(binding.getExecutionToken())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS, "The page publication attempt is no longer current");
    }
  }

  private PreparedPolicy view(final ApplicationFormWritePolicyPublicationEntity intent) {
    return new PreparedPolicy(intent.getId(), intent.getStatus(),
        decode(intent.getCommandJson(), RecordWritePolicyPublicationCommand.class), intent.getTargetFingerprint(),
        intent.getInspectionJson() == null ? null : decode(intent.getInspectionJson(), RecordWritePolicyPublicationView.class),
        intent.getCancellationJson() == null ? null : decode(intent.getCancellationJson(), RecordWritePolicyCancellationView.class));
  }

  private String encode(final Object value) {
    try {
      String json = mapper.writeValueAsString(value);
      if (json.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) {
        throw failure(ApplicationFormErrorCode.DOCUMENT_INVALID, "The policy inspection intent exceeds its storage budget");
      }
      return json;
    } catch (JsonProcessingException exception) {
      throw new ApplicationFormException(ApplicationFormErrorCode.PERSISTENCE_FAILED, "Policy intent serialization failed", exception);
    }
  }

  private <T> T decode(final String value, final Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new ApplicationFormException(ApplicationFormErrorCode.PERSISTENCE_FAILED, "Stored policy intent is invalid", exception);
    }
  }

  private static ApplicationFormException failure(final ApplicationFormErrorCode code, final String message) {
    return new ApplicationFormException(code, message);
  }

  private record LockedPublication(ApplicationFormEntity form, ApplicationFormPageBindingEntity binding) {
  }

  /** Server-only immutable work; callers never recreate the DNA command from a changed draft. */
  public record PreparedPolicy(String id, FormWritePolicyPublicationStatus status,
      RecordWritePolicyPublicationCommand command, String targetFingerprint, RecordWritePolicyPublicationView inspection,
      RecordWritePolicyCancellationView cancellation) {

    /** Retains the publication-only coordination contract. */
    public PreparedPolicy(final String id, final FormWritePolicyPublicationStatus status,
        final RecordWritePolicyPublicationCommand command, final String targetFingerprint, final RecordWritePolicyPublicationView inspection) {
      this(id, status, command, targetFingerprint, inspection, null);
    }
  }
}
