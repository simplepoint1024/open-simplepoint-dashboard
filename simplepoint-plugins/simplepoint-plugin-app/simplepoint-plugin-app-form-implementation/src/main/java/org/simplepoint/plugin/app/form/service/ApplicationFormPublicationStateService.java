package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStep;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.PrepareApplicationFormDataCommand;
import org.simplepoint.plugin.app.form.configuration.ApplicationFormPublicationProperties;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPublicationEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.PreparedDnaSchema;
import org.simplepoint.plugin.app.form.service.FormRequestContext.FormActor;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short APP transactions surrounding remote DNA schema work. */
@Service
public class ApplicationFormPublicationStateService {

  private static final Pattern IDEMPOTENCY_KEY =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");
  private static final Set<ApplicationFormPublicationStatus> OPEN_STATES =
      Set.of(
          ApplicationFormPublicationStatus.VALIDATING,
          ApplicationFormPublicationStatus.VERSION_FROZEN,
          ApplicationFormPublicationStatus.DATA_PREPARING,
          ApplicationFormPublicationStatus.DATA_READY,
          ApplicationFormPublicationStatus.RESOURCE_BINDING,
          ApplicationFormPublicationStatus.ACTIVATING,
          ApplicationFormPublicationStatus.RETRYING);

  private final EntityManager entityManager;
  private final FormRequestContext requestContext;
  private final FormStorageProjectionCompiler storageCompiler;
  private final FormDocumentValidator documentValidator;
  private final FormDocumentCodec documentCodec;
  private final Clock clock;
  private final long leaseMs;

  /** Creates the state boundary over APP persistence and ownership checks. */
  public ApplicationFormPublicationStateService(
      final EntityManager entityManager,
      final FormRequestContext requestContext,
      final FormStorageProjectionCompiler storageCompiler,
      final FormDocumentValidator documentValidator,
      final FormDocumentCodec documentCodec,
      final Clock clock,
      final ApplicationFormPublicationProperties properties) {
    this.entityManager = entityManager;
    this.requestContext = requestContext;
    this.storageCompiler = storageCompiler;
    this.documentValidator = documentValidator;
    this.documentCodec = documentCodec;
    this.clock = clock;
    this.leaseMs = properties.getLeaseMs();
  }

  /** Claims one new, failed, or stale DNA preparation attempt. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PublicationClaim claim(
      final String applicationId,
      final String formId,
      final PrepareApplicationFormDataCommand command) {
    FormActor actor = requestContext.requireActor();
    String appId = required(applicationId, 36, "applicationId");
    String ownerFormId = required(formId, 36, "formId");
    ValidatedCommand validated = validate(command);
    ApplicationFormEntity form = requireForm(actor, appId, ownerFormId, true);
    if (form.getStatus() == ApplicationFormStatus.ARCHIVED) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.FORM_ARCHIVED, "Archived forms cannot be published");
    }
    ApplicationFormVersionEntity version =
        requireVersion(actor.tenantId(), form.getId(), validated.formVersion());
    ApplicationFormPublicationEntity keyed =
        findByKey(actor.tenantId(), validated.idempotencyKey());
    if (keyed != null
        && (!form.getId().equals(keyed.getFormId())
            || validated.formVersion() != keyed.getFormVersion())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_IDEMPOTENCY_CONFLICT,
          "The publication idempotency key belongs to another form version");
    }
    ApplicationFormPublicationEntity existing =
        findByVersion(actor.tenantId(), form.getId(), validated.formVersion(), true);
    if (existing != null) {
      return claimExisting(existing, form, version);
    }
    documentValidator.validatePublication(documentCodec.decode(version.getDocumentJson()));
    requireNoOtherOpenPublication(actor.tenantId(), form.getId(), validated.formVersion());
    return create(actor, form, version, validated.idempotencyKey());
  }

  /** Reads a durable version result; an intent may exist before data preparation has begun. */
  @Transactional(readOnly = true)
  public ApplicationFormPublicationView getForVersion(
      final String applicationId, final String formId, final int version) {
    FormActor actor = requestContext.requireActor();
    requireForm(actor, applicationId, formId, false);
    var publication = findByVersion(actor.tenantId(), formId, version, false);
    return publication == null ? null : view(publication);
  }

  /** Persists a nonterminal DNA migration observed after a recoverable call. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ApplicationFormPublicationView recordDataProgress(
      final String publicationId, final String executionToken, final PreparedDnaSchema prepared) {
    FormActor actor = requestContext.requireActor();
    ApplicationFormPublicationEntity publication =
        requirePublication(actor.tenantId(), publicationId, true);
    requireExecution(publication, executionToken);
    publication.setMigrationId(prepared.migrationId());
    publication.setLeaseUntil(now().plusMillis(leaseMs));
    publication.setUpdatedAt(now());
    entityManager.flush();
    return view(publication);
  }

  /** Commits the DNA binding only after the target physical schema is active. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ApplicationFormPublicationView completeData(
      final String publicationId, final String executionToken, final PreparedDnaSchema prepared) {
    if (!prepared.active()) {
      throw new IllegalArgumentException("DNA schema must be active before completion");
    }
    FormActor actor = requestContext.requireActor();
    ApplicationFormPublicationEntity publication =
        requirePublication(actor.tenantId(), publicationId, true);
    if (publication.getStatus() == ApplicationFormPublicationStatus.DATA_READY) {
      return view(publication);
    }
    requireExecution(publication, executionToken);
    ApplicationFormEntity form = requireFormById(actor.tenantId(), publication.getFormId(), true);
    ApplicationFormVersionEntity version =
        requireVersion(actor.tenantId(), publication.getFormId(), publication.getFormVersion());
    requireConsistentBinding(form, version, prepared);
    form.setPhysicalModelId(prepared.physicalModelId());
    form.setUpdatedAt(now());
    version.setPhysicalModelVersion(prepared.physicalModelVersion());
    version.setDetailBindingsJson(prepared.detailBindingsJson());
    publication.setMigrationId(prepared.migrationId());
    publication.setStatus(ApplicationFormPublicationStatus.DATA_READY);
    publication.setStep(ApplicationFormPublicationStep.PAGE_RESOURCE);
    publication.setErrorCode(null);
    publication.setExecutionToken(null);
    publication.setLeaseUntil(null);
    publication.setUpdatedAt(now());
    entityManager.flush();
    return view(publication);
  }

  /** Records a stable retryable failure without retaining downstream details. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ApplicationFormPublicationView fail(
      final String publicationId,
      final String executionToken,
      final ApplicationFormErrorCode errorCode) {
    FormActor actor = requestContext.requireActor();
    ApplicationFormPublicationEntity publication =
        requirePublication(actor.tenantId(), publicationId, true);
    if (!executionToken.equals(publication.getExecutionToken())) {
      return view(publication);
    }
    if (publication.getStatus() == ApplicationFormPublicationStatus.DATA_READY
        || publication.getStatus() == ApplicationFormPublicationStatus.ACTIVE) {
      return view(publication);
    }
    publication.setStatus(ApplicationFormPublicationStatus.FAILED);
    publication.setErrorCode(errorCode.name());
    publication.setExecutionToken(null);
    publication.setLeaseUntil(null);
    publication.setUpdatedAt(now());
    entityManager.flush();
    return view(publication);
  }

  /** Reads one tenant-owned publication after checking its form route. */
  @Transactional(readOnly = true)
  public ApplicationFormPublicationView get(
      final String applicationId, final String formId, final String publicationId) {
    FormActor actor = requestContext.requireActor();
    String appId = required(applicationId, 36, "applicationId");
    String ownerFormId = required(formId, 36, "formId");
    requireForm(actor, appId, ownerFormId, false);
    ApplicationFormPublicationEntity publication =
        requirePublication(actor.tenantId(), required(publicationId, 36, "publicationId"), false);
    if (!ownerFormId.equals(publication.getFormId())) {
      throw publicationNotFound();
    }
    return view(publication);
  }

  private PublicationClaim claimExisting(
      final ApplicationFormPublicationEntity publication,
      final ApplicationFormEntity form,
      final ApplicationFormVersionEntity version) {
    if (!OPEN_STATES.contains(publication.getStatus())
        && publication.getStatus() != ApplicationFormPublicationStatus.FAILED) {
      return new PublicationClaim(false, null, view(publication));
    }
    if (publication.getStatus() == ApplicationFormPublicationStatus.DATA_READY
        || publication.getStatus() == ApplicationFormPublicationStatus.RESOURCE_BINDING
        || publication.getStatus() == ApplicationFormPublicationStatus.ACTIVATING) {
      return new PublicationClaim(false, null, view(publication));
    }
    Instant leaseUntil = publication.getLeaseUntil();
    if (publication.getStatus() != ApplicationFormPublicationStatus.FAILED
        && leaseUntil != null
        && leaseUntil.isAfter(now())) {
      return new PublicationClaim(false, null, view(publication));
    }
    boolean recoverStaleDna = staleForRecovery(publication);
    claimExecution(publication, ApplicationFormPublicationStatus.RETRYING);
    entityManager.flush();
    return new PublicationClaim(
        true, work(publication, form, version, recoverStaleDna), view(publication));
  }

  private PublicationClaim create(
      final FormActor actor,
      final ApplicationFormEntity form,
      final ApplicationFormVersionEntity version,
      final String idempotencyKey) {
    Instant timestamp = now();
    ApplicationFormPublicationEntity publication = new ApplicationFormPublicationEntity();
    publication.setId(UUID.randomUUID().toString());
    publication.setTenantId(actor.tenantId());
    publication.setFormId(form.getId());
    publication.setFormVersion(version.getVersionNumber());
    publication.setIdempotencyKey(idempotencyKey);
    publication.setStep(ApplicationFormPublicationStep.DNA_SCHEMA);
    publication.setAttempt(0);
    publication.setCreatedAt(timestamp);
    publication.setUpdatedAt(timestamp);
    claimExecution(publication, ApplicationFormPublicationStatus.DATA_PREPARING);
    try {
      entityManager.persist(publication);
      entityManager.flush();
    } catch (PersistenceException exception) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_IDEMPOTENCY_CONFLICT,
          "Form publication conflicts with another request",
          exception);
    }
    return new PublicationClaim(true, work(publication, form, version, false), view(publication));
  }

  private void claimExecution(
      final ApplicationFormPublicationEntity publication,
      final ApplicationFormPublicationStatus status) {
    publication.setStatus(status);
    publication.setStep(ApplicationFormPublicationStep.DNA_SCHEMA);
    publication.setAttempt(publication.getAttempt() + 1);
    publication.setExecutionToken(UUID.randomUUID().toString());
    publication.setLeaseUntil(now().plusMillis(leaseMs));
    publication.setErrorCode(null);
    publication.setUpdatedAt(now());
  }

  private PublicationWork work(
      final ApplicationFormPublicationEntity publication,
      final ApplicationFormEntity form,
      final ApplicationFormVersionEntity version,
      final boolean recoverStaleDna) {
    return new PublicationWork(
        publication.getId(),
        publication.getExecutionToken(),
        form.getApplicationId(),
        form.getId(),
        form.getName(),
        form.getPhysicalModelId(),
        version.getVersionNumber(),
        recoverStaleDna,
        storageCompiler.decode(version.getStorageSchemaJson()),
        version.getStorageChecksum(),
        documentCodec.decode(version.getDocumentJson()));
  }

  private boolean staleForRecovery(final ApplicationFormPublicationEntity publication) {
    Instant boundary =
        publication.getStatus() == ApplicationFormPublicationStatus.FAILED
            ? publication.getUpdatedAt() == null
                ? null
                : publication.getUpdatedAt().plusMillis(leaseMs)
            : publication.getLeaseUntil();
    return boundary != null && !boundary.isAfter(now());
  }

  private void requireNoOtherOpenPublication(
      final String tenantId, final String formId, final int targetVersion) {
    boolean present =
        entityManager
                .createQuery(
                    "select count(publication) from ApplicationFormPublicationEntity publication"
                        + " where publication.tenantId = :tenant and publication.formId = :form and"
                        + " publication.formVersion <> :version and publication.status in :states",
                    Long.class)
                .setParameter("tenant", tenantId)
                .setParameter("form", formId)
                .setParameter("version", targetVersion)
                .setParameter("states", OPEN_STATES)
                .getSingleResult()
            > 0;
    if (present) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS,
          "Another form version is still being published");
    }
  }

  private ApplicationFormEntity requireForm(
      final FormActor actor, final String applicationId, final String formId, final boolean lock) {
    var query =
        entityManager
            .createQuery(
                "from ApplicationFormEntity form where form.tenantId = :tenant "
                    + "and form.applicationId = :application and form.id = :form",
                ApplicationFormEntity.class)
            .setParameter("tenant", actor.tenantId())
            .setParameter("application", applicationId)
            .setParameter("form", formId);
    if (lock) {
      query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    }
    return query
        .getResultStream()
        .findFirst()
        .orElseThrow(ApplicationFormPublicationStateService::formNotFound);
  }

  private ApplicationFormEntity requireFormById(
      final String tenantId, final String formId, final boolean lock) {
    var query =
        entityManager
            .createQuery(
                "from ApplicationFormEntity form where form.tenantId = :tenant "
                    + "and form.id = :form",
                ApplicationFormEntity.class)
            .setParameter("tenant", tenantId)
            .setParameter("form", formId);
    if (lock) {
      query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    }
    return query
        .getResultStream()
        .findFirst()
        .orElseThrow(ApplicationFormPublicationStateService::formNotFound);
  }

  private ApplicationFormVersionEntity requireVersion(
      final String tenantId, final String formId, final int version) {
    return entityManager
        .createQuery(
            "from ApplicationFormVersionEntity version where version.tenantId = :tenant "
                + "and version.formId = :form and version.versionNumber = :version",
            ApplicationFormVersionEntity.class)
        .setParameter("tenant", tenantId)
        .setParameter("form", formId)
        .setParameter("version", version)
        .getResultStream()
        .findFirst()
        .orElseThrow(
            () ->
                new ApplicationFormException(
                    ApplicationFormErrorCode.VERSION_NOT_FOUND, "Form version was not found"));
  }

  private ApplicationFormPublicationEntity findByKey(
      final String tenantId, final String idempotencyKey) {
    return entityManager
        .createQuery(
            "from ApplicationFormPublicationEntity publication "
                + "where publication.tenantId = :tenant "
                + "and publication.idempotencyKey = :key",
            ApplicationFormPublicationEntity.class)
        .setParameter("tenant", tenantId)
        .setParameter("key", idempotencyKey)
        .getResultStream()
        .findFirst()
        .orElse(null);
  }

  private ApplicationFormPublicationEntity findByVersion(
      final String tenantId, final String formId, final int version, final boolean lock) {
    return entityManager
        .createQuery(
            "from ApplicationFormPublicationEntity publication "
                + "where publication.tenantId = :tenant and publication.formId = :form "
                + "and publication.formVersion = :version",
            ApplicationFormPublicationEntity.class)
        .setParameter("tenant", tenantId)
        .setParameter("form", formId)
        .setParameter("version", version)
        .setLockMode(lock ? LockModeType.PESSIMISTIC_WRITE : LockModeType.NONE)
        .getResultStream()
        .findFirst()
        .orElse(null);
  }

  private ApplicationFormPublicationEntity requirePublication(
      final String tenantId, final String publicationId, final boolean lock) {
    var query =
        entityManager
            .createQuery(
                "from ApplicationFormPublicationEntity publication "
                    + "where publication.tenantId = :tenant and publication.id = :publication",
                ApplicationFormPublicationEntity.class)
            .setParameter("tenant", tenantId)
            .setParameter("publication", publicationId);
    if (lock) {
      query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    }
    return query
        .getResultStream()
        .findFirst()
        .orElseThrow(ApplicationFormPublicationStateService::publicationNotFound);
  }

  private static void requireExecution(
      final ApplicationFormPublicationEntity publication, final String executionToken) {
    if (executionToken == null
        || !executionToken.equals(publication.getExecutionToken())
        || publication.getStatus() != ApplicationFormPublicationStatus.DATA_PREPARING
            && publication.getStatus() != ApplicationFormPublicationStatus.RETRYING) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS,
          "The publication attempt is no longer current");
    }
  }

  private static void requireConsistentBinding(
      final ApplicationFormEntity form,
      final ApplicationFormVersionEntity version,
      final PreparedDnaSchema prepared) {
    if (form.getPhysicalModelId() != null
            && !form.getPhysicalModelId().equals(prepared.physicalModelId())
        || version.getPhysicalModelVersion() != null
            && version.getPhysicalModelVersion() != prepared.physicalModelVersion()) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_FAILED,
          "The form publication conflicts with its existing DNA binding");
    }
  }

  private static ValidatedCommand validate(final PrepareApplicationFormDataCommand command) {
    if (command == null || command.formVersion() < 1) {
      throw invalid("A positive formVersion is required");
    }
    String key = command.idempotencyKey() == null ? null : command.idempotencyKey().trim();
    if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
      throw invalid("idempotencyKey has an invalid format");
    }
    return new ValidatedCommand(command.formVersion(), key);
  }

  private static String required(final String value, final int maxLength, final String name) {
    if (value == null || value.isBlank() || value.trim().length() > maxLength) {
      throw invalid(name + " is required");
    }
    return value.trim();
  }

  private Instant now() {
    return clock.instant();
  }

  private static ApplicationFormPublicationView view(
      final ApplicationFormPublicationEntity publication) {
    return new ApplicationFormPublicationView(
        publication.getId(),
        publication.getFormId(),
        publication.getFormVersion(),
        publication.getStatus(),
        publication.getStep(),
        publication.getMigrationId(),
        publication.getErrorCode(),
        publication.getAttempt(),
        publication.getCreatedAt(),
        publication.getUpdatedAt());
  }

  private static ApplicationFormException invalid(final String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);
  }

  private static ApplicationFormException formNotFound() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.FORM_NOT_FOUND, "Form was not found in the active application");
  }

  private static ApplicationFormException publicationNotFound() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.PUBLICATION_NOT_FOUND, "Form publication was not found");
  }

  private record ValidatedCommand(int formVersion, String idempotencyKey) {}

  /** Result of atomically claiming or observing one publication attempt. */
  public record PublicationClaim(
      boolean execute, PublicationWork work, ApplicationFormPublicationView view) {}

  /** Immutable DNA work restored from an APP form version. */
  public record PublicationWork(
      String publicationId,
      String executionToken,
      String applicationId,
      String formId,
      String displayName,
      String physicalModelId,
      int formVersion,
      boolean recoverStaleDna,
      PhysicalSchemaDefinition schema,
      String storageChecksum,
      org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument document) {
    /** Preserves the immutable runtime identity and optional child bindings. */
    public PublicationWork(
        String publicationId,
        String executionToken,
        String applicationId,
        String formId,
        String displayName,
        String physicalModelId,
        int formVersion,
        boolean recoverStaleDna,
        PhysicalSchemaDefinition schema,
        String storageChecksum) {
      this(
          publicationId,
          executionToken,
          applicationId,
          formId,
          displayName,
          physicalModelId,
          formVersion,
          recoverStaleDna,
          schema,
          storageChecksum,
          null);
    }
  }
}
