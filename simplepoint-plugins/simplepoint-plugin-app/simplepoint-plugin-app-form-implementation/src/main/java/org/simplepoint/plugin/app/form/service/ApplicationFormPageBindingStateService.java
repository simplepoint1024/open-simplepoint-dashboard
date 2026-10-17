package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ActivateApplicationPageBinding;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStep;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationPageTarget;
import org.simplepoint.plugin.app.form.api.model.PublishApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationPageAccessPort;
import org.simplepoint.plugin.app.form.configuration.ApplicationFormPublicationProperties;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPublicationEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormReleaseKeyEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormWritePolicyPublicationEntity;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.app.form.service.FormPageResourceCoordinator.PreparedPageResource;
import org.simplepoint.plugin.app.form.service.FormRequestContext.FormActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short APP transactions surrounding remote managed-resource publication. */
@Service
public class ApplicationFormPageBindingStateService {

  private static final String MAIN_SCENE = "main";
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
      "[A-Za-z0-9][A-Za-z0-9._:-]{0,79}"
  );
  private static final Set<ApplicationFormPageBindingStatus> OPEN_STATES = Set.of(
      ApplicationFormPageBindingStatus.RESOURCE_PREPARING,
      ApplicationFormPageBindingStatus.RESOURCE_READY,
      ApplicationFormPageBindingStatus.ACTIVATING
  );

  private static final Set<ApplicationFormPageBindingStatus> TERMINAL_STATES = Set.of(
      ApplicationFormPageBindingStatus.RETIRED, ApplicationFormPageBindingStatus.CANCELLED);

  private final EntityManager entityManager;
  private final FormRequestContext requestContext;
  private final ApplicationPageAccessPort pageAccess;
  private final Clock clock;
  private final FormWritePolicyPublicationStateService policies;
  private final long leaseMs;

  /** Creates the page publication state boundary over APP-owned persistence. */
  public ApplicationFormPageBindingStateService(
      final EntityManager entityManager,
      final FormRequestContext requestContext,
      final ApplicationPageAccessPort pageAccess,
      final Clock clock,
      final ApplicationFormPublicationProperties properties,
      final FormWritePolicyPublicationStateService policies
  ) {
    this.entityManager = entityManager;
    this.requestContext = requestContext;
    this.pageAccess = pageAccess;
    this.clock = clock;
    this.policies = policies;
    this.leaseMs = properties.getLeaseMs();
  }

  /** Claims a new, failed, or stale page resource publication. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BindingClaim claim(
      final String applicationId,
      final String formId,
      final PublishApplicationFormCommand command
  ) {
    FormActor actor = requestContext.requireActor();
    String appId = required(applicationId, 36, "applicationId");
    String ownerFormId = required(formId, 36, "formId");
    ValidatedCommand target = validate(command);
    final ApplicationPageTarget page = pageAccess.requirePageForUpdate(
        actor.tenantId(),
        appId,
        target.pageId()
    );
    ApplicationFormEntity form = requireForm(actor.tenantId(), appId, ownerFormId, true);
    if (form.getStatus() == ApplicationFormStatus.ARCHIVED) {
      throw failure(ApplicationFormErrorCode.FORM_ARCHIVED, "Archived forms cannot be published");
    }
    requireVersion(actor.tenantId(), ownerFormId, target.formVersion());
    requireDataReady(actor.tenantId(), ownerFormId, target.formVersion());
    ApplicationFormPageBindingEntity keyed = findByKey(
        actor.tenantId(),
        target.idempotencyKey()
    );
    if (keyed != null && !sameTarget(keyed, appId, ownerFormId, target)) {
      throw failure(
          ApplicationFormErrorCode.PUBLICATION_IDEMPOTENCY_CONFLICT,
          "The page publication idempotency key belongs to another target"
      );
    }
    ApplicationFormPageBindingEntity existing = keyed != null ? keyed : findTarget(
        actor.tenantId(),
        target.pageId(),
        ownerFormId,
        target.formVersion(),
        true
    );
    if (existing != null) {
      if (keyed == null) {
        rememberKey(existing, target.idempotencyKey());
      }
      if (existing.getStatus() != ApplicationFormPageBindingStatus.ACTIVE
          && !TERMINAL_STATES.contains(existing.getStatus())) {
        FormWritePolicyPublicationGuard.requireAvailable(entityManager, actor.tenantId(), ownerFormId, target.pageId(), existing.getId());
        expireOrRejectOtherWork(actor.tenantId(), target.pageId(), existing.getId());
      }
      return claimExisting(existing, page, form);
    }
    FormWritePolicyPublicationGuard.requireAvailable(entityManager, actor.tenantId(), ownerFormId, target.pageId(), null);
    expireOrRejectOtherWork(actor.tenantId(), target.pageId(), null);
    return create(actor, form, page, target, true);
  }

  /** Persists the page/version intent before any remote DNA work can commit. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ApplicationFormPageBindingView reserve(
      final String applicationId, final String formId, final PublishApplicationFormCommand command
  ) {
    FormActor actor = requestContext.requireActor();
    String appId = required(applicationId, 36, "applicationId");
    String ownerFormId = required(formId, 36, "formId");
    ValidatedCommand target = validate(command);
    final ApplicationPageTarget page = pageAccess.requirePageForUpdate(actor.tenantId(), appId, target.pageId());
    ApplicationFormEntity form = requireForm(actor.tenantId(), appId, ownerFormId, true);
    if (form.getStatus() == ApplicationFormStatus.ARCHIVED) {
      throw failure(ApplicationFormErrorCode.FORM_ARCHIVED, "Archived forms cannot be published");
    }
    requireVersion(actor.tenantId(), ownerFormId, target.formVersion());
    ApplicationFormPageBindingEntity keyed = findByKey(actor.tenantId(), target.idempotencyKey());
    if (keyed != null && !sameTarget(keyed, appId, ownerFormId, target)) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_IDEMPOTENCY_CONFLICT,
          "The page publication idempotency key belongs to another target");
    }
    ApplicationFormPageBindingEntity existing = keyed != null ? keyed : findTarget(actor.tenantId(), target.pageId(),
        ownerFormId, target.formVersion(), true);
    if (existing != null) {
      if (keyed == null) {
        rememberKey(existing, target.idempotencyKey());
      }
      if (existing.getStatus() != ApplicationFormPageBindingStatus.ACTIVE
          && !TERMINAL_STATES.contains(existing.getStatus())) {
        FormWritePolicyPublicationGuard.requireAvailable(entityManager, actor.tenantId(), ownerFormId, target.pageId(), existing.getId());
      }
      if (existing.getStatus() == ApplicationFormPageBindingStatus.FAILED) {
        existing.setStatus(ApplicationFormPageBindingStatus.DATA_PREPARING);
        existing.setErrorCode(null);
        existing.setExecutionToken(null);
        existing.setLeaseUntil(null);
        existing.setUpdatedAt(now());
        entityManager.flush();
      }
      return view(existing);
    }
    FormWritePolicyPublicationGuard.requireAvailable(entityManager, actor.tenantId(), ownerFormId, target.pageId(), null);
    expireOrRejectOtherWork(actor.tenantId(), target.pageId(), null);
    return create(actor, form, page, target, false).view();
  }

  /** Resolves a retry exclusively from tenant-owned durable metadata. */
  @Transactional(readOnly = true)
  public PublishApplicationFormCommand resumeCommand(
      final String applicationId, final String formId, final String releaseId
  ) {
    FormActor actor = requestContext.requireActor();
    requireForm(actor.tenantId(), applicationId, formId, false);
    ApplicationFormPageBindingEntity binding = requireBinding(actor.tenantId(), releaseId, false);
    if (!applicationId.equals(binding.getApplicationId()) || !formId.equals(binding.getFormId())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_NOT_FOUND, "Publication was not found");
    }
    return new PublishApplicationFormCommand(binding.getFormVersion(), binding.getPageId(),
        binding.getIdempotencyKey());
  }

  /** Reads the saved release without reacquiring its execution or changing its request keys. */
  @Transactional(readOnly = true)
  public ApplicationFormPageBindingView get(final String applicationId, final String formId, final String releaseId) {
    resumeCommand(applicationId, formId, releaseId);
    return view(requireBinding(requestContext.requireActor().tenantId(), releaseId, false));
  }

  /** Cancels before resource work, or atomically claims the durable target cancellation decision. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BindingClaim claimCancellation(final String applicationId, final String formId, final String releaseId) {
    FormActor actor = requestContext.requireActor();
    ApplicationFormPageBindingEntity binding = requireBinding(actor.tenantId(), releaseId, false);
    if (!applicationId.equals(binding.getApplicationId()) || !formId.equals(binding.getFormId())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_NOT_FOUND, "Publication was not found");
    }
    final ApplicationPageTarget page = pageAccess.requirePageForUpdate(actor.tenantId(), applicationId, binding.getPageId());
    final ApplicationFormEntity form = requireForm(actor.tenantId(), applicationId, formId, true);
    entityManager.refresh(binding, LockModeType.PESSIMISTIC_WRITE);
    if (binding.getStatus() == ApplicationFormPageBindingStatus.ACTIVE || TERMINAL_STATES.contains(binding.getStatus())) {
      return new BindingClaim(false, null, view(binding));
    }
    FormWritePolicyPublicationGuard.requireAvailable(entityManager, actor.tenantId(), formId, binding.getPageId(), binding.getId());
    var policy = entityManager.createQuery("from ApplicationFormWritePolicyPublicationEntity p "
            + "where p.tenantId = :tenant and p.bindingId = :binding", ApplicationFormWritePolicyPublicationEntity.class)
        .setParameter("tenant", actor.tenantId()).setParameter("binding", binding.getId()).getResultStream().findFirst().orElse(null);
    var status = policy == null ? null : policy.getStatus();
    if (!FormReleaseCancellation.allowed(binding.getStatus(), binding.getAttempt(), status)
        && status != FormWritePolicyPublicationStatus.TARGET_READY) {
      throw failure(ApplicationFormErrorCode.PAGE_BINDING_CONFLICT,
          "This release has already entered resource activation; resume its original publication");
    }
    if (policy == null) {
      binding.setStatus(ApplicationFormPageBindingStatus.CANCELLED);
      binding.setExecutionToken(null);
      binding.setLeaseUntil(null);
      binding.setErrorCode(null);
      binding.setUpdatedAt(now());
      entityManager.flush();
      return new BindingClaim(false, null, view(binding));
    }
    if ((status == FormWritePolicyPublicationStatus.CANCELLING || status == FormWritePolicyPublicationStatus.TARGET_READY)
        && binding.getLeaseUntil() != null && binding.getLeaseUntil().isAfter(now())) {
      return new BindingClaim(false, null, view(binding));
    }
    claimExecution(binding);
    binding.setStatus(ApplicationFormPageBindingStatus.ACTIVATING);
    BindingWork work = work(binding, page, form);
    entityManager.flush(); // The joined policy transaction refreshes the binding under the same lock.
    policies.requestCancellationWithinAttempt(work);
    entityManager.flush();
    return new BindingClaim(true, work, view(binding));
  }

  /** Claims a stored policy decision before schema or resource preparation can run again. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BindingClaim claimPolicyRecovery(final String applicationId, final String formId, final String releaseId) {
    FormActor actor = requestContext.requireActor();
    ApplicationFormPageBindingEntity binding = requireBinding(actor.tenantId(), releaseId, false);
    if (!applicationId.equals(binding.getApplicationId()) || !formId.equals(binding.getFormId())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_NOT_FOUND, "Publication was not found");
    }
    final ApplicationPageTarget page = pageAccess.requirePageForUpdate(actor.tenantId(), applicationId, binding.getPageId());
    final ApplicationFormEntity form = requireForm(actor.tenantId(), applicationId, formId, true);
    entityManager.refresh(binding, LockModeType.PESSIMISTIC_WRITE);
    boolean present = !entityManager.createQuery("select p.id from ApplicationFormWritePolicyPublicationEntity p "
            + "where p.tenantId = :tenant and p.bindingId = :binding", String.class)
        .setParameter("tenant", actor.tenantId()).setParameter("binding", binding.getId()).getResultList().isEmpty();
    if (!present) {
      return null;
    }
    if (binding.getStatus() == ApplicationFormPageBindingStatus.ACTIVE || TERMINAL_STATES.contains(binding.getStatus())
        || binding.getLeaseUntil() != null && binding.getLeaseUntil().isAfter(now())) {
      return new BindingClaim(false, null, view(binding));
    }
    FormWritePolicyPublicationGuard.requireAvailable(entityManager, actor.tenantId(), formId, binding.getPageId(), binding.getId());
    claimExecution(binding);
    binding.setStatus(ApplicationFormPageBindingStatus.ACTIVATING);
    entityManager.flush();
    return new BindingClaim(true, work(binding, page, form), view(binding));
  }

  /** Reconstructs an active projection for authorized replay without reopening its publication. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ActiveResourceProjection activeProjection(
      final String applicationId, final String formId, final String bindingId
  ) {
    FormActor actor = requestContext.requireActor();
    ApplicationFormPageBindingEntity binding = requireBinding(actor.tenantId(), bindingId, false);
    if (!applicationId.equals(binding.getApplicationId()) || !formId.equals(binding.getFormId())) {
      throw failure(ApplicationFormErrorCode.PUBLICATION_NOT_FOUND, "Publication was not found");
    }
    final ApplicationPageTarget page = pageAccess.requirePageForUpdate(actor.tenantId(), applicationId, binding.getPageId());
    entityManager.refresh(binding, LockModeType.PESSIMISTIC_WRITE);
    if (binding.getStatus() != ApplicationFormPageBindingStatus.ACTIVE) {
      throw failure(ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED, "The page publication is no longer active");
    }
    var active = pageAccess.requireActiveBinding(actor.tenantId(), applicationId, binding.getPageId());
    if (!binding.getFormId().equals(active.formId()) || binding.getFormVersion() != active.formVersion()
        || !binding.getResourceCode().equals(active.resourceCode())) {
      throw failure(ApplicationFormErrorCode.PAGE_BINDING_CONFLICT, "Active page metadata does not match the publication");
    }
    ApplicationFormEntity form = requireForm(actor.tenantId(), applicationId, formId, false);
    return new ActiveResourceProjection(work(binding, page, form), new PreparedPageResource(
        actor.tenantId(), binding.getResourceManagedKey(), binding.getResourceId(), binding.getResourceCode(),
        binding.getRuntimePath(), true));
  }

  /** Records data-stage failure without overwriting an advanced resource/active binding. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void preparationFailed(final String releaseId, final ApplicationFormErrorCode code) {
    FormActor actor = requestContext.requireActor();
    ApplicationFormPageBindingEntity binding = requireBinding(actor.tenantId(), releaseId, true);
    if (binding.getStatus() == ApplicationFormPageBindingStatus.DATA_PREPARING) {
      binding.setStatus(ApplicationFormPageBindingStatus.FAILED);
      binding.setErrorCode(code.name());
      binding.setUpdatedAt(now());
      entityManager.flush();
    }
  }

  /** Persists the complete disabled resource subtree before activation begins. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ApplicationFormPageBindingView resourceReady(
      final String bindingId,
      final String executionToken,
      final PreparedPageResource resource
  ) {
    FormActor actor = requestContext.requireActor();
    ApplicationFormPageBindingEntity binding = requireBinding(actor.tenantId(), bindingId, true);
    requireExecution(binding, executionToken, ApplicationFormPageBindingStatus.RESOURCE_PREPARING);
    requireResourceIdentity(binding, resource);
    binding.setResourceId(resource.resourceId());
    binding.setResourceCode(resource.resourceCode());
    binding.setRuntimePath(resource.runtimePath());
    binding.setStatus(ApplicationFormPageBindingStatus.ACTIVATING);
    binding.setLeaseUntil(now().plusMillis(leaseMs));
    binding.setErrorCode(null);
    binding.setUpdatedAt(now());
    entityManager.flush();
    return view(binding);
  }

  /** Atomically activates the page binding after the managed resource is visible. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ApplicationFormPageBindingView complete(
      final String bindingId,
      final String executionToken,
      final PreparedPageResource resource
  ) {
    FormActor actor = requestContext.requireActor();
    ApplicationFormPageBindingEntity binding = requireBinding(actor.tenantId(), bindingId, false);
    // Use the same catalog/page -> form -> binding lock order as policy preparation and claim.
    final ApplicationPageTarget page = pageAccess.requirePageForUpdate(actor.tenantId(), binding.getApplicationId(), binding.getPageId());
    final ApplicationFormEntity form = requireForm(
        actor.tenantId(),
        binding.getApplicationId(),
        binding.getFormId(),
        true
    );
    entityManager.refresh(binding, LockModeType.PESSIMISTIC_WRITE);
    if (binding.getStatus() == ApplicationFormPageBindingStatus.ACTIVE) {
      return view(binding);
    }
    FormWritePolicyPublicationGuard.requireAvailable(entityManager, actor.tenantId(), binding.getFormId(), binding.getPageId(), binding.getId());
    requireExecution(binding, executionToken, ApplicationFormPageBindingStatus.ACTIVATING);
    requireResourceIdentity(binding, resource);
    if (!resource.enabled()) {
      throw new IllegalArgumentException("Managed page resource must be active before completion");
    }
    pageAccess.activateBinding(new ActivateApplicationPageBinding(
        actor.tenantId(),
        binding.getApplicationId(),
        binding.getPageId(),
        binding.getFormId(),
        binding.getFormVersion(),
        resource.resourceId(),
        resource.resourceCode(),
        resource.runtimePath()
    ));
    retirePreviousBindings(binding);
    // Retire the old ACTIVE row before activating this one: the database enforces
    // one active binding per page with a non-deferrable partial unique index.
    entityManager.flush();
    Instant timestamp = now();
    binding.setStatus(ApplicationFormPageBindingStatus.ACTIVE);
    binding.setResourceId(resource.resourceId());
    binding.setResourceCode(resource.resourceCode());
    binding.setRuntimePath(resource.runtimePath());
    binding.setActivatedAt(timestamp);
    binding.setExecutionToken(null);
    binding.setLeaseUntil(null);
    binding.setErrorCode(null);
    binding.setUpdatedAt(timestamp);
    if (form.getPublishedVersion() == null
        || binding.getFormVersion() > form.getPublishedVersion()) {
      form.setPublishedVersion(binding.getFormVersion());
    }
    form.setStatus(ApplicationFormStatus.PUBLISHED);
    form.setUpdatedAt(timestamp);
    activateDataPublication(binding, timestamp);
    policies.activate(work(binding, page, form));
    entityManager.flush();
    return view(binding);
  }

  /** Records a retryable page publication failure. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ApplicationFormPageBindingView fail(
      final String bindingId,
      final String executionToken,
      final ApplicationFormErrorCode errorCode
  ) {
    FormActor actor = requestContext.requireActor();
    ApplicationFormPageBindingEntity binding = requireBinding(actor.tenantId(), bindingId, true);
    if (!executionToken.equals(binding.getExecutionToken())
        || binding.getStatus() == ApplicationFormPageBindingStatus.ACTIVE
        || TERMINAL_STATES.contains(binding.getStatus())) {
      return view(binding);
    }
    binding.setStatus(errorCode == ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED
        ? ApplicationFormPageBindingStatus.RETIRED : ApplicationFormPageBindingStatus.FAILED);
    binding.setErrorCode(errorCode.name());
    binding.setExecutionToken(null);
    binding.setLeaseUntil(null);
    binding.setUpdatedAt(now());
    entityManager.flush();
    return view(binding);
  }

  private BindingClaim claimExisting(
      final ApplicationFormPageBindingEntity binding,
      final ApplicationPageTarget page,
      final ApplicationFormEntity form
  ) {
    if (binding.getStatus() == ApplicationFormPageBindingStatus.ACTIVE
        || TERMINAL_STATES.contains(binding.getStatus())) {
      return new BindingClaim(false, null, view(binding));
    }
    if (binding.getStatus() != ApplicationFormPageBindingStatus.FAILED
        && binding.getLeaseUntil() != null && binding.getLeaseUntil().isAfter(now())) {
      return new BindingClaim(false, null, view(binding));
    }
    claimExecution(binding);
    entityManager.flush();
    return new BindingClaim(true, work(binding, page, form), view(binding));
  }

  private BindingClaim create(
      final FormActor actor,
      final ApplicationFormEntity form,
      final ApplicationPageTarget page,
      final ValidatedCommand target,
      final boolean execute
  ) {
    Instant timestamp = now();
    ApplicationFormPageBindingEntity binding = new ApplicationFormPageBindingEntity();
    binding.setId(UUID.randomUUID().toString());
    binding.setTenantId(actor.tenantId());
    binding.setApplicationId(form.getApplicationId());
    binding.setPageId(page.pageId());
    binding.setFormId(form.getId());
    binding.setFormVersion(target.formVersion());
    // The owning page is locked by reserve/claim, serializing generation allocation.
    Long previousGeneration = entityManager.createQuery(
            "select max(b.generation) from ApplicationFormPageBindingEntity b "
                + "where b.tenantId = :tenant and b.pageId = :page", Long.class)
        .setParameter("tenant", actor.tenantId()).setParameter("page", page.pageId()).getSingleResult();
    binding.setGeneration(Math.addExact(previousGeneration == null ? 0 : previousGeneration, 1));
    binding.setSceneId(MAIN_SCENE);
    binding.setIdempotencyKey(target.idempotencyKey());
    binding.setResourceManagedKey(managedKey(page.pageId()));
    binding.setAttempt(0);
    binding.setPolicyProtocolVersion(1);
    binding.setCreatedAt(timestamp);
    binding.setUpdatedAt(timestamp);
    if (execute) {
      claimExecution(binding);
    } else {
      binding.setStatus(ApplicationFormPageBindingStatus.DATA_PREPARING);
    }
    try {
      entityManager.persist(binding);
      rememberKey(binding, target.idempotencyKey());
      entityManager.flush();
    } catch (PersistenceException exception) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PAGE_BINDING_CONFLICT,
          "The page binding conflicts with another publication",
          exception
      );
    }
    return new BindingClaim(execute, execute ? work(binding, page, form) : null, view(binding));
  }

  private void claimExecution(final ApplicationFormPageBindingEntity binding) {
    binding.setStatus(ApplicationFormPageBindingStatus.RESOURCE_PREPARING);
    binding.setAttempt(binding.getAttempt() + 1);
    binding.setExecutionToken(UUID.randomUUID().toString());
    binding.setLeaseUntil(now().plusMillis(leaseMs));
    binding.setErrorCode(null);
    binding.setUpdatedAt(now());
  }

  private void expireOrRejectOtherWork(
      final String tenantId, final String pageId, final String excludedId
  ) {
    List<ApplicationFormPageBindingEntity> open = entityManager.createQuery(
            "from ApplicationFormPageBindingEntity binding where binding.tenantId = :tenant "
                + "and binding.pageId = :page and binding.status in :states",
            ApplicationFormPageBindingEntity.class
        )
        .setParameter("tenant", tenantId)
        .setParameter("page", pageId)
        .setParameter("states", OPEN_STATES)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .getResultList();
    for (ApplicationFormPageBindingEntity binding : open) {
      if (binding.getId().equals(excludedId)) {
        continue;
      }
      if (binding.getLeaseUntil() != null && binding.getLeaseUntil().isAfter(now())) {
        throw failure(
            ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS,
            "Another publication is still updating this page"
        );
      }
      binding.setStatus(ApplicationFormPageBindingStatus.FAILED);
      binding.setErrorCode(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS.name());
      binding.setExecutionToken(null);
      binding.setLeaseUntil(null);
      binding.setUpdatedAt(now());
    }
  }

  private void retirePreviousBindings(final ApplicationFormPageBindingEntity active) {
    List<ApplicationFormPageBindingEntity> previous = entityManager.createQuery(
            "from ApplicationFormPageBindingEntity binding where binding.tenantId = :tenant "
                + "and binding.pageId = :page and binding.status not in :terminal "
                + "and binding.id <> :id",
            ApplicationFormPageBindingEntity.class
        )
        .setParameter("tenant", active.getTenantId())
        .setParameter("page", active.getPageId())
        .setParameter("terminal", TERMINAL_STATES)
        .setParameter("id", active.getId())
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .getResultList();
    for (ApplicationFormPageBindingEntity binding : previous) {
      binding.setStatus(ApplicationFormPageBindingStatus.RETIRED);
      binding.setExecutionToken(null);
      binding.setLeaseUntil(null);
      binding.setUpdatedAt(now());
    }
  }

  private void activateDataPublication(
      final ApplicationFormPageBindingEntity binding,
      final Instant timestamp
  ) {
    ApplicationFormPublicationEntity publication = entityManager.createQuery(
            "from ApplicationFormPublicationEntity publication "
                + "where publication.tenantId = :tenant and publication.formId = :form "
                + "and publication.formVersion = :version",
            ApplicationFormPublicationEntity.class
        )
        .setParameter("tenant", binding.getTenantId())
        .setParameter("form", binding.getFormId())
        .setParameter("version", binding.getFormVersion())
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .getResultStream()
        .findFirst()
        .orElseThrow(() -> failure(
            ApplicationFormErrorCode.PUBLICATION_NOT_FOUND,
            "The data publication is unavailable"
        ));
    publication.setStatus(ApplicationFormPublicationStatus.ACTIVE);
    publication.setStep(ApplicationFormPublicationStep.ACTIVATE);
    publication.setErrorCode(null);
    publication.setUpdatedAt(timestamp);
  }

  private void requireDataReady(
      final String tenantId,
      final String formId,
      final int formVersion
  ) {
    ApplicationFormPublicationStatus status = entityManager.createQuery(
            "select publication.status from ApplicationFormPublicationEntity publication "
                + "where publication.tenantId = :tenant and publication.formId = :form "
                + "and publication.formVersion = :version",
            ApplicationFormPublicationStatus.class
        )
        .setParameter("tenant", tenantId)
        .setParameter("form", formId)
        .setParameter("version", formVersion)
        .getResultStream()
        .findFirst()
        .orElseThrow(() -> failure(
            ApplicationFormErrorCode.PUBLICATION_NOT_FOUND,
            "The form data schema has not been prepared"
        ));
    if (status != ApplicationFormPublicationStatus.DATA_READY
        && status != ApplicationFormPublicationStatus.ACTIVE) {
      throw failure(
          ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS,
          "The form data schema is not ready"
      );
    }
  }

  private ApplicationFormEntity requireForm(
      final String tenantId,
      final String applicationId,
      final String formId,
      final boolean lock
  ) {
    var query = entityManager.createQuery(
            "from ApplicationFormEntity form where form.tenantId = :tenant "
                + "and form.applicationId = :application and form.id = :form",
            ApplicationFormEntity.class
        )
        .setParameter("tenant", tenantId)
        .setParameter("application", applicationId)
        .setParameter("form", formId);
    if (lock) {
      query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    }
    return query.getResultStream().findFirst()
        .orElseThrow(() -> failure(ApplicationFormErrorCode.FORM_NOT_FOUND, "Form was not found"));
  }

  private void requireVersion(final String tenantId, final String formId, final int version) {
    boolean present = !entityManager.createQuery(
            "select version.id from ApplicationFormVersionEntity version "
                + "where version.tenantId = :tenant and version.formId = :form "
                + "and version.versionNumber = :version",
            String.class
        )
        .setParameter("tenant", tenantId)
        .setParameter("form", formId)
        .setParameter("version", version)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
    if (!present) {
      throw failure(ApplicationFormErrorCode.VERSION_NOT_FOUND, "Form version was not found");
    }
  }

  private void rememberKey(final ApplicationFormPageBindingEntity binding, final String key) {
    var alias = new ApplicationFormReleaseKeyEntity();
    alias.setId(UUID.randomUUID().toString());
    alias.setTenantId(binding.getTenantId());
    alias.setRequestKey(key);
    alias.setBindingId(binding.getId());
    alias.setCreatedAt(now());
    try {
      entityManager.persist(alias);
      entityManager.flush();
    } catch (PersistenceException failure) {
      throw new ApplicationFormException(ApplicationFormErrorCode.PUBLICATION_IDEMPOTENCY_CONFLICT,
          "The publication request key is already assigned", failure);
    }
  }

  private ApplicationFormPageBindingEntity findByKey(
      final String tenantId,
      final String idempotencyKey
  ) {
    return entityManager.createQuery(
            "from ApplicationFormPageBindingEntity binding where binding.tenantId = :tenant "
                + "and (binding.idempotencyKey = :key or binding.id in "
                + "(select k.bindingId from ApplicationFormReleaseKeyEntity k where k.tenantId = :tenant and k.requestKey = :key))",
            ApplicationFormPageBindingEntity.class
        )
        .setParameter("tenant", tenantId)
        .setParameter("key", idempotencyKey)
        .getResultStream()
        .findFirst()
        .orElse(null);
  }

  private ApplicationFormPageBindingEntity findTarget(
      final String tenantId,
      final String pageId,
      final String formId,
      final int formVersion,
      final boolean lock
  ) {
    var query = entityManager.createQuery(
            "from ApplicationFormPageBindingEntity binding where binding.tenantId = :tenant "
                + "and binding.pageId = :page and binding.formId = :form "
                + "and binding.formVersion = :version and binding.status not in :terminal",
            ApplicationFormPageBindingEntity.class
        )
        .setParameter("tenant", tenantId)
        .setParameter("page", pageId)
        .setParameter("form", formId)
        .setParameter("version", formVersion).setParameter("terminal", TERMINAL_STATES);
    if (lock) {
      query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    }
    return query.getResultStream().findFirst().orElse(null);
  }

  private ApplicationFormPageBindingEntity requireBinding(
      final String tenantId,
      final String bindingId,
      final boolean lock
  ) {
    var query = entityManager.createQuery(
            "from ApplicationFormPageBindingEntity binding where binding.tenantId = :tenant "
                + "and binding.id = :id",
            ApplicationFormPageBindingEntity.class
        )
        .setParameter("tenant", tenantId)
        .setParameter("id", bindingId);
    if (lock) {
      query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    }
    return query.getResultStream().findFirst().orElseThrow(() -> failure(
        ApplicationFormErrorCode.PAGE_NOT_FOUND,
        "Page binding was not found"
    ));
  }

  private static void requireExecution(
      final ApplicationFormPageBindingEntity binding,
      final String token,
      final ApplicationFormPageBindingStatus status
  ) {
    if (token == null || !token.equals(binding.getExecutionToken())
        || binding.getStatus() != status) {
      throw failure(
          ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS,
          "The page publication attempt is no longer current"
      );
    }
  }

  private static void requireResourceIdentity(
      final ApplicationFormPageBindingEntity binding,
      final PreparedPageResource resource
  ) {
    if (resource == null
        || !binding.getTenantId().equals(resource.tenantId())
        || !binding.getResourceManagedKey().equals(resource.managedKey())) {
      throw failure(
          ApplicationFormErrorCode.PAGE_BINDING_CONFLICT,
          "The managed page resource does not match its publication"
      );
    }
  }

  private static boolean sameTarget(
      final ApplicationFormPageBindingEntity binding,
      final String applicationId,
      final String formId,
      final ValidatedCommand target
  ) {
    return binding.getApplicationId().equals(applicationId)
        && binding.getPageId().equals(target.pageId())
        && binding.getFormId().equals(formId)
        && binding.getFormVersion() == target.formVersion();
  }

  private static ValidatedCommand validate(final PublishApplicationFormCommand command) {
    if (command == null || command.formVersion() < 1) {
      throw failure(ApplicationFormErrorCode.DOCUMENT_INVALID, "A positive formVersion is required");
    }
    String pageId = required(command.pageId(), 36, "pageId");
    String key = command.idempotencyKey() == null ? null : command.idempotencyKey().trim();
    if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
      throw failure(ApplicationFormErrorCode.DOCUMENT_INVALID, "idempotencyKey is invalid");
    }
    return new ValidatedCommand(command.formVersion(), pageId, key);
  }

  private static String required(final String value, final int maxLength, final String name) {
    if (value == null || value.isBlank() || value.trim().length() > maxLength) {
      throw failure(ApplicationFormErrorCode.DOCUMENT_INVALID, name + " is required");
    }
    return value.trim();
  }

  private static String managedKey(final String pageId) {
    return "page:" + pageId;
  }

  private BindingWork work(
      final ApplicationFormPageBindingEntity binding,
      final ApplicationPageTarget page,
      final ApplicationFormEntity form
  ) {
    return new BindingWork(
        binding.getId(),
        binding.getExecutionToken(),
        binding.getTenantId(),
        binding.getApplicationId(),
        binding.getPageId(),
        binding.getFormId(),
        binding.getFormVersion(),
        requiredPhysicalModel(form),
        binding.getResourceManagedKey(),
        page.name(),
        page.description(),
        page.sortOrder(),
        binding.getGeneration(),
        binding.getPolicyProtocolVersion(),
        page.navigationGroupName()
    );
  }

  private Instant now() {
    return clock.instant();
  }

  private static String requiredPhysicalModel(final ApplicationFormEntity form) {
    if (form.getPhysicalModelId() == null || form.getPhysicalModelId().isBlank()) {
      throw failure(
          ApplicationFormErrorCode.PUBLICATION_FAILED,
          "The form data model is not ready for page publication"
      );
    }
    return form.getPhysicalModelId();
  }

  private static ApplicationFormPageBindingView view(
      final ApplicationFormPageBindingEntity binding
  ) {
    return new ApplicationFormPageBindingView(
        binding.getId(),
        binding.getApplicationId(),
        binding.getPageId(),
        binding.getFormId(),
        binding.getFormVersion(),
        binding.getSceneId(),
        binding.getStatus(),
        binding.getResourceId(),
        binding.getResourceCode(),
        binding.getRuntimePath(),
        binding.getErrorCode(),
        binding.getAttempt(),
        binding.getActivatedAt(),
        binding.getCreatedAt(),
        binding.getUpdatedAt()
    );
  }

  private static ApplicationFormException failure(
      final ApplicationFormErrorCode code,
      final String message
  ) {
    return new ApplicationFormException(code, message);
  }

  /** Claim result kept inside the orchestration layer. */
  public record BindingClaim(
      boolean execute,
      BindingWork work,
      ApplicationFormPageBindingView view
  ) {
  }

  /** Durable identity used to reconcile legacy resource associations with the same generation. */
  public record ActiveResourceProjection(BindingWork work, PreparedPageResource resource) {
  }

  /** Immutable input for remote managed-resource calls. */
  public record BindingWork(
      String bindingId,
      String executionToken,
      String tenantId,
      String applicationId,
      String pageId,
      String formId,
      int formVersion,
      String physicalModelId,
      String managedKey,
      String pageName,
      String pageDescription,
      int sortOrder,
      long generation,
      int policyProtocolVersion,
      String navigationGroupName
  ) {
    /** Preserves existing publication callers and parent identities. */
    public BindingWork(String bindingId, String executionToken, String tenantId,
        String applicationId, String pageId, String formId, int formVersion,
        String physicalModelId, String managedKey, String pageName, String pageDescription,
        int sortOrder, long generation, int policyProtocolVersion) {
      this(bindingId, executionToken, tenantId, applicationId, pageId, formId, formVersion,
          physicalModelId, managedKey, pageName, pageDescription, sortOrder, generation,
          policyProtocolVersion, null);
    }

    /** New attempts use the fenced policy protocol; persisted legacy attempts carry an explicit zero. */
    public BindingWork(final String bindingId, final String executionToken, final String tenantId,
        final String applicationId, final String pageId, final String formId, final int formVersion,
        final String physicalModelId, final String managedKey, final String pageName, final String pageDescription,
        final int sortOrder, final long generation) {
      this(bindingId, executionToken, tenantId, applicationId, pageId, formId, formVersion, physicalModelId,
          managedKey, pageName, pageDescription, sortOrder, generation, 1);
    }
  }

  private record ValidatedCommand(int formVersion, String pageId, String idempotencyKey) {
  }
}
