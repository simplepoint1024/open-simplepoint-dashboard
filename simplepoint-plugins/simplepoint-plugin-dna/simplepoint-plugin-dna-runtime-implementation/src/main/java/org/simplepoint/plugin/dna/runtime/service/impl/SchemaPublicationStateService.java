package org.simplepoint.plugin.dna.runtime.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEvent;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditOutcome;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditPublisher;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStepStatus;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStepView;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelTransitions;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationEntity;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationStepEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.repository.SchemaMigrationRepository;
import org.simplepoint.plugin.dna.runtime.repository.SchemaMigrationStepRepository;
import org.simplepoint.plugin.dna.runtime.service.schema.InitialSchemaPlan;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.PlannedSchemaStep;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short control-plane transactions used around nontransactional target-database DDL. */
@Service
public class SchemaPublicationStateService {

  private final PhysicalModelRepository modelRepository;
  private final PhysicalModelBindingRepository bindingRepository;
  private final PhysicalModelVersionRepository versionRepository;
  private final SchemaMigrationRepository migrationRepository;
  private final SchemaMigrationStepRepository stepRepository;
  private final PhysicalSchemaCodec schemaCodec;
  private final DnaRuntimeAuditPublisher auditPublisher;

  /** Creates the durable state coordinator. */
  public SchemaPublicationStateService(
      final PhysicalModelRepository modelRepository,
      final PhysicalModelBindingRepository bindingRepository,
      final PhysicalModelVersionRepository versionRepository,
      final SchemaMigrationRepository migrationRepository,
      final SchemaMigrationStepRepository stepRepository,
      final PhysicalSchemaCodec schemaCodec,
      final DnaRuntimeAuditPublisher auditPublisher
  ) {
    this.modelRepository = modelRepository;
    this.bindingRepository = bindingRepository;
    this.versionRepository = versionRepository;
    this.migrationRepository = migrationRepository;
    this.stepRepository = stepRepository;
    this.schemaCodec = schemaCodec;
    this.auditPublisher = auditPublisher;
  }

  /** Claims a new target version while holding the model row lock. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PublicationClaim claim(
      final RuntimeActor actor,
      final String modelId,
      final SchemaPublicationCommand command
  ) {
    PhysicalModelEntity model = requireLockedModel(actor.tenantId(), modelId);
    SchemaMigrationEntity existing = migrationRepository.findByTargetVersion(
        actor.tenantId(),
        modelId,
        command.targetVersion()
    ).orElse(null);
    if (existing != null) {
      return new PublicationClaim(false, work(actor.tenantId(), existing, model));
    }
    requireExpectedVersion(model, command.expectedLockVersion());
    if (command.targetVersion() != model.getLatestVersion()) {
      throw conflict("Only the latest immutable model version can be published");
    }
    migrationRepository.findNonterminalByModel(actor.tenantId(), modelId)
        .ifPresent(value -> {
          throw conflict("Another schema publication is already active for this model");
        });
    if (model.getStatus() != PhysicalModelStatus.DRAFT
        && model.getStatus() != PhysicalModelStatus.FAILED) {
      throw conflict("The physical model is not ready to start publication");
    }
    PhysicalModelVersionEntity version = requireVersion(
        actor.tenantId(),
        modelId,
        command.targetVersion()
    );
    final PhysicalModelBindingEntity binding = requireBinding(actor.tenantId(), modelId);
    SchemaMigrationEntity migration = new SchemaMigrationEntity();
    migration.setId(UUID.randomUUID().toString());
    migration.setTenantId(actor.tenantId());
    migration.setPhysicalModelId(modelId);
    migration.setFromVersion(model.getActiveVersion());
    migration.setTargetVersion(command.targetVersion());
    migration.setTargetSchemaChecksum(version.getSchemaChecksum());
    migration.setStatus(SchemaMigrationStatus.VALIDATING);
    migration.setExecutionToken(UUID.randomUUID().toString());
    migration.setStartedAt(Instant.now());
    migration.setCreatedBy(actor.actorId());
    migration.setUpdatedBy(actor.actorId());
    transition(model, PhysicalModelStatus.VALIDATING, actor.actorId());
    try {
      migrationRepository.saveAndFlush(migration);
    } catch (DataIntegrityViolationException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT,
          "Schema publication conflicts with another request",
          exception
      );
    }
    auditPublisher.publish(audit(
        DnaRuntimeAuditEventType.SCHEMA_PUBLICATION_STARTED,
        DnaRuntimeAuditOutcome.SUCCEEDED,
        null,
        model,
        binding,
        migration,
        actor
    ));
    return new PublicationClaim(true, work(actor.tenantId(), migration, model));
  }

  /** Reclaims a failed migration and resets incomplete steps for an explicit retry. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PublicationClaim claimRetry(
      final RuntimeActor actor,
      final String migrationId,
      final long expectedLockVersion
  ) {
    SchemaMigrationEntity migration = requireLockedMigration(actor.tenantId(), migrationId);
    PhysicalModelEntity model = requireLockedModel(
        actor.tenantId(),
        migration.getPhysicalModelId()
    );
    requireExpectedVersion(model, expectedLockVersion);
    if (migration.getStatus() != SchemaMigrationStatus.FAILED
        || model.getStatus() != PhysicalModelStatus.FAILED) {
      throw conflict("Only a failed schema publication can be retried");
    }
    migrationRepository.findNonterminalByModel(actor.tenantId(), model.getId())
        .ifPresent(value -> {
          throw conflict("Another schema publication is already active for this model");
        });
    transition(model, PhysicalModelStatus.VALIDATING, actor.actorId());
    migration.setStatus(SchemaMigrationStatus.VALIDATING);
    migration.setExecutionToken(UUID.randomUUID().toString());
    migration.setErrorCode(null);
    migration.setStartedAt(Instant.now());
    migration.setFinishedAt(null);
    migration.setUpdatedBy(actor.actorId());
    for (SchemaMigrationStepEntity step : stepRepository.findByMigration(
        actor.tenantId(),
        migrationId
    )) {
      if (step.getStatus() == SchemaMigrationStepStatus.FAILED
          || step.getStatus() == SchemaMigrationStepStatus.RUNNING) {
        step.setStatus(SchemaMigrationStepStatus.PENDING);
        step.setErrorCode(null);
        step.setStartedAt(null);
        step.setFinishedAt(null);
        step.setUpdatedBy(actor.actorId());
        stepRepository.save(step);
      }
    }
    return new PublicationClaim(true, work(actor.tenantId(), migration, model));
  }

  /** Persists the validated plan and advances the model to READY. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void ready(
      final RuntimeActor actor,
      final String migrationId,
      final String executionToken,
      final InitialSchemaPlan plan
  ) {
    SchemaMigrationEntity migration = requireLockedMigration(actor.tenantId(), migrationId);
    requireExecutionToken(migration, executionToken);
    final PhysicalModelEntity model = requireLockedModel(
        actor.tenantId(),
        migration.getPhysicalModelId()
    );
    requireStatus(migration, SchemaMigrationStatus.VALIDATING);
    requireModelStatus(model, PhysicalModelStatus.VALIDATING);
    List<SchemaMigrationStepEntity> existing = stepRepository.findByMigration(
        actor.tenantId(),
        migrationId
    );
    if (existing.isEmpty()) {
      plan.steps().forEach(step -> stepRepository.save(toEntity(step, migration, actor)));
    } else {
      validatePersistedSteps(existing, plan.steps());
    }
    migration.setDialectId(plan.dialectId());
    migration.setDatabaseVersion(plan.database().productVersion());
    migration.setActualSchemaChecksum(plan.actualSnapshot().checksum());
    migration.setTargetPhysicalChecksum(plan.targetSnapshot().checksum());
    migration.setRisk(plan.risk());
    migration.setStatus(SchemaMigrationStatus.READY);
    migration.setUpdatedBy(actor.actorId());
    transition(model, PhysicalModelStatus.READY, actor.actorId());
  }

  /** Starts execution after the validated plan and steps are committed. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void start(
      final RuntimeActor actor,
      final String migrationId,
      final String executionToken
  ) {
    SchemaMigrationEntity migration = requireLockedMigration(actor.tenantId(), migrationId);
    requireExecutionToken(migration, executionToken);
    final PhysicalModelEntity model = requireLockedModel(
        actor.tenantId(),
        migration.getPhysicalModelId()
    );
    requireStatus(migration, SchemaMigrationStatus.READY);
    requireModelStatus(model, PhysicalModelStatus.READY);
    migration.setStatus(SchemaMigrationStatus.MIGRATING);
    migration.setUpdatedBy(actor.actorId());
    transition(model, PhysicalModelStatus.MIGRATING, actor.actorId());
  }

  /** Records a step attempt before native DDL is sent to the target database. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String beginStep(
      final RuntimeActor actor,
      final String migrationId,
      final String executionToken,
      final int sequence
  ) {
    SchemaMigrationEntity migration = requireLockedMigration(actor.tenantId(), migrationId);
    requireExecutionToken(migration, executionToken);
    requireStatus(migration, SchemaMigrationStatus.MIGRATING);
    touch(migration, actor.actorId());
    SchemaMigrationStepEntity step = requireStep(actor.tenantId(), migrationId, sequence);
    if (step.getStatus() == SchemaMigrationStepStatus.SUCCEEDED
        || step.getStatus() == SchemaMigrationStepStatus.SKIPPED) {
      return step.getId();
    }
    step.setStatus(SchemaMigrationStepStatus.RUNNING);
    step.setAttempts(step.getAttempts() + 1);
    step.setStartedAt(Instant.now());
    step.setFinishedAt(null);
    step.setErrorCode(null);
    step.setUpdatedBy(actor.actorId());
    return step.getId();
  }

  /** Marks one DDL step as executed or already satisfied. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finishStep(
      final RuntimeActor actor,
      final String migrationId,
      final String executionToken,
      final String stepId,
      final SchemaMigrationStepStatus status
  ) {
    if (status != SchemaMigrationStepStatus.SUCCEEDED
        && status != SchemaMigrationStepStatus.SKIPPED) {
      throw new IllegalArgumentException("A finished step must be SUCCEEDED or SKIPPED");
    }
    SchemaMigrationEntity migration = requireLockedMigration(actor.tenantId(), migrationId);
    requireExecutionToken(migration, executionToken);
    requireStatus(migration, SchemaMigrationStatus.MIGRATING);
    touch(migration, actor.actorId());
    SchemaMigrationStepEntity step = stepRepository.findActiveById(actor.tenantId(), stepId)
        .orElseThrow(() -> notFound("Schema migration step was not found"));
    if (!migrationId.equals(step.getMigrationId())) {
      throw conflict("Schema migration step does not belong to this publication");
    }
    step.setStatus(status);
    step.setFinishedAt(Instant.now());
    step.setErrorCode(null);
    step.setUpdatedBy(actor.actorId());
  }

  /** Advances a fully executed publication to post-DDL verification. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void verifying(
      final RuntimeActor actor,
      final String migrationId,
      final String executionToken
  ) {
    SchemaMigrationEntity migration = requireLockedMigration(actor.tenantId(), migrationId);
    requireExecutionToken(migration, executionToken);
    final PhysicalModelEntity model = requireLockedModel(
        actor.tenantId(),
        migration.getPhysicalModelId()
    );
    requireStatus(migration, SchemaMigrationStatus.MIGRATING);
    boolean incomplete = stepRepository.findByMigration(actor.tenantId(), migrationId).stream()
        .anyMatch(step -> step.getStatus() != SchemaMigrationStepStatus.SUCCEEDED
            && step.getStatus() != SchemaMigrationStepStatus.SKIPPED);
    if (incomplete) {
      throw conflict("Schema publication still has incomplete steps");
    }
    migration.setStatus(SchemaMigrationStatus.VERIFYING);
    migration.setUpdatedBy(actor.actorId());
    transition(model, PhysicalModelStatus.VERIFYING, actor.actorId());
  }

  /** Activates the verified target version after cache invalidation succeeds. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SchemaPublicationView activate(
      final RuntimeActor actor,
      final String migrationId,
      final String executionToken,
      final String verifiedChecksum
  ) {
    SchemaMigrationEntity migration = requireLockedMigration(actor.tenantId(), migrationId);
    requireExecutionToken(migration, executionToken);
    final PhysicalModelEntity model = requireLockedModel(
        actor.tenantId(),
        migration.getPhysicalModelId()
    );
    requireStatus(migration, SchemaMigrationStatus.VERIFYING);
    if (!Objects.equals(migration.getTargetPhysicalChecksum(), verifiedChecksum)) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_VERIFICATION_FAILED,
          "Published table structure does not match the target snapshot"
      );
    }
    migration.setVerifiedSchemaChecksum(verifiedChecksum);
    migration.setStatus(SchemaMigrationStatus.ACTIVE);
    migration.setFinishedAt(Instant.now());
    migration.setErrorCode(null);
    migration.setUpdatedBy(actor.actorId());
    model.setActiveVersion(migration.getTargetVersion());
    transition(model, PhysicalModelStatus.ACTIVE, actor.actorId());
    PhysicalModelBindingEntity binding = requireBinding(actor.tenantId(), model.getId());
    auditPublisher.publish(audit(
        DnaRuntimeAuditEventType.SCHEMA_PUBLICATION_SUCCEEDED,
        DnaRuntimeAuditOutcome.SUCCEEDED,
        null,
        model,
        binding,
        migration,
        actor
    ));
    return view(actor.tenantId(), migration);
  }

  /** Persists a stable failure state even when target DDL has already committed. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(
      final RuntimeActor actor,
      final String migrationId,
      final String executionToken,
      final DnaRuntimeErrorCode errorCode
  ) {
    SchemaMigrationEntity migration = migrationRepository.findActiveByIdForUpdate(
        actor.tenantId(),
        migrationId
    ).orElse(null);
    if (migration == null
        || migration.getStatus() == SchemaMigrationStatus.ACTIVE
        || !Objects.equals(migration.getExecutionToken(), executionToken)) {
      return;
    }
    final PhysicalModelEntity model = requireLockedModel(
        actor.tenantId(),
        migration.getPhysicalModelId()
    );
    migration.setStatus(SchemaMigrationStatus.FAILED);
    migration.setErrorCode(errorCode.name());
    migration.setFinishedAt(Instant.now());
    migration.setUpdatedBy(actor.actorId());
    stepRepository.findByMigration(actor.tenantId(), migrationId).stream()
        .filter(step -> step.getStatus() == SchemaMigrationStepStatus.RUNNING)
        .forEach(step -> {
          step.setStatus(SchemaMigrationStepStatus.FAILED);
          step.setErrorCode(errorCode.name());
          step.setFinishedAt(Instant.now());
          step.setUpdatedBy(actor.actorId());
        });
    if (model.getStatus() != PhysicalModelStatus.FAILED
        && PhysicalModelTransitions.canTransition(model.getStatus(), PhysicalModelStatus.FAILED)) {
      transition(model, PhysicalModelStatus.FAILED, actor.actorId());
    }
    PhysicalModelBindingEntity binding = requireBinding(actor.tenantId(), model.getId());
    auditPublisher.publish(audit(
        DnaRuntimeAuditEventType.SCHEMA_PUBLICATION_FAILED,
        DnaRuntimeAuditOutcome.FAILED,
        errorCode,
        model,
        binding,
        migration,
        actor
    ));
  }

  /** Fences an inactive publication and records an operator-visible interrupted failure. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SchemaPublicationView recoverStale(
      final RuntimeActor actor,
      final String migrationId,
      final long expectedLockVersion,
      final Instant staleBefore
  ) {
    SchemaMigrationEntity migration = requireLockedMigration(actor.tenantId(), migrationId);
    PhysicalModelEntity model = requireLockedModel(
        actor.tenantId(),
        migration.getPhysicalModelId()
    );
    requireExpectedVersion(model, expectedLockVersion);
    if (migration.getStatus().terminal()) {
      throw conflict("Only a nonterminal schema publication can be recovered");
    }
    PhysicalModelStatus expectedModelStatus = PhysicalModelStatus.valueOf(
        migration.getStatus().name()
    );
    requireModelStatus(model, expectedModelStatus);
    Instant lastActivity = migration.getUpdatedAt() == null
        ? migration.getStartedAt() : migration.getUpdatedAt();
    if (lastActivity == null || lastActivity.isAfter(staleBefore)) {
      throw conflict("Schema publication is still within its activity window");
    }

    DnaRuntimeErrorCode errorCode = DnaRuntimeErrorCode.SCHEMA_PUBLICATION_INTERRUPTED;
    migration.setExecutionToken(UUID.randomUUID().toString());
    migration.setStatus(SchemaMigrationStatus.FAILED);
    migration.setErrorCode(errorCode.name());
    migration.setFinishedAt(Instant.now());
    migration.setUpdatedBy(actor.actorId());
    stepRepository.findByMigration(actor.tenantId(), migrationId).stream()
        .filter(step -> step.getStatus() == SchemaMigrationStepStatus.RUNNING)
        .forEach(step -> {
          step.setStatus(SchemaMigrationStepStatus.FAILED);
          step.setErrorCode(errorCode.name());
          step.setFinishedAt(Instant.now());
          step.setUpdatedBy(actor.actorId());
        });
    transition(model, PhysicalModelStatus.FAILED, actor.actorId());
    PhysicalModelBindingEntity binding = requireBinding(actor.tenantId(), model.getId());
    auditPublisher.publish(audit(
        DnaRuntimeAuditEventType.SCHEMA_PUBLICATION_RECOVERED,
        DnaRuntimeAuditOutcome.FAILED,
        errorCode,
        model,
        binding,
        migration,
        actor
    ));
    return view(actor.tenantId(), migration);
  }

  /** Reads one tenant-scoped migration projection. */
  @Transactional(readOnly = true)
  public SchemaPublicationView get(final String tenantId, final String migrationId) {
    return view(tenantId, migrationRepository.findActiveById(tenantId, migrationId)
        .orElseThrow(() -> notFound("Schema migration was not found")));
  }

  /** Loads trusted planning inputs without exposing the physical binding to callers. */
  @Transactional(readOnly = true)
  public PlanningWork planningWork(
      final RuntimeActor actor,
      final String modelId,
      final int targetVersion
  ) {
    PhysicalModelEntity model = modelRepository.findActiveById(actor.tenantId(), modelId)
        .orElseThrow(() -> new DnaRuntimeException(
            DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
            "Physical model was not found in the active tenant"
        ));
    if (targetVersion < 1 || targetVersion > model.getLatestVersion()) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.INVALID_ARGUMENT,
          "Target model version is outside the registered version range"
      );
    }
    PhysicalModelBindingEntity binding = requireBinding(actor.tenantId(), modelId);
    PhysicalModelVersionEntity version = requireVersion(
        actor.tenantId(),
        modelId,
        targetVersion
    );
    PhysicalSchemaDefinition currentSchema = model.getActiveVersion() == null
        ? null : schemaCodec.decode(requireVersion(
        actor.tenantId(),
        modelId,
        model.getActiveVersion()
    ).getSchemaJson());
    return new PlanningWork(
        modelId,
        model.getDisplayName(),
        targetVersion,
        new PhysicalSchemaTarget(
            modelId,
            new RuntimeMetadataTarget(
                binding.getDataSourceId(),
                binding.getCatalogName(),
                binding.getSchemaName(),
                binding.getTableName()
            )
        ),
        currentSchema,
        schemaCodec.decode(version.getSchemaJson())
    );
  }

  /** Returns current step rows for execution checksum validation. */
  @Transactional(readOnly = true)
  public List<PersistedStep> steps(final String tenantId, final String migrationId) {
    return stepRepository.findByMigration(tenantId, migrationId).stream()
        .map(step -> new PersistedStep(
            step.getId(),
            step.getSequence(),
            step.getOperation().name(),
            step.getStatementChecksum(),
            step.getStatus()
        ))
        .toList();
  }

  private PublicationWork work(
      final String tenantId,
      final SchemaMigrationEntity migration,
      final PhysicalModelEntity model
  ) {
    PhysicalModelBindingEntity binding = requireBinding(tenantId, model.getId());
    PhysicalModelVersionEntity version = requireVersion(
        tenantId,
        model.getId(),
        migration.getTargetVersion()
    );
    PhysicalSchemaDefinition currentSchema = migration.getFromVersion() == null
        ? null : schemaCodec.decode(requireVersion(
        tenantId,
        model.getId(),
        migration.getFromVersion()
    ).getSchemaJson());
    return new PublicationWork(
        migration.getId(),
        migration.getExecutionToken(),
        model.getId(),
        model.getDisplayName(),
        model.getAppId(),
        model.getFormId(),
        migration.getTargetVersion(),
        new PhysicalSchemaTarget(
            model.getId(),
            new RuntimeMetadataTarget(
                binding.getDataSourceId(),
                binding.getCatalogName(),
                binding.getSchemaName(),
                binding.getTableName()
            )
        ),
        currentSchema,
        schemaCodec.decode(version.getSchemaJson())
    );
  }

  private SchemaPublicationView view(
      final String tenantId,
      final SchemaMigrationEntity migration
  ) {
    List<SchemaMigrationStepView> steps = stepRepository.findByMigration(
        tenantId,
        migration.getId()
    ).stream().map(SchemaPublicationStateService::stepView).toList();
    return new SchemaPublicationView(
        migration.getId(),
        migration.getPhysicalModelId(),
        migration.getFromVersion(),
        migration.getTargetVersion(),
        migration.getStatus(),
        migration.getRisk(),
        migration.getDialectId(),
        migration.getActualSchemaChecksum(),
        migration.getTargetPhysicalChecksum(),
        migration.getErrorCode(),
        migration.getStartedAt(),
        migration.getFinishedAt(),
        steps
    );
  }

  private static SchemaMigrationStepView stepView(final SchemaMigrationStepEntity step) {
    return new SchemaMigrationStepView(
        step.getSequence(),
        step.getOperation(),
        step.getLogicalObject(),
        step.getRisk(),
        step.getStatus(),
        step.getAttempts(),
        step.getErrorCode(),
        step.getStartedAt(),
        step.getFinishedAt()
    );
  }

  private static SchemaMigrationStepEntity toEntity(
      final PlannedSchemaStep step,
      final SchemaMigrationEntity migration,
      final RuntimeActor actor
  ) {
    SchemaMigrationStepEntity entity = new SchemaMigrationStepEntity();
    entity.setId(UUID.randomUUID().toString());
    entity.setTenantId(actor.tenantId());
    entity.setMigrationId(migration.getId());
    entity.setSequence(step.sequence());
    entity.setOperation(step.operation());
    entity.setLogicalObject(step.logicalObject());
    entity.setRisk(step.risk());
    entity.setPayloadJson(step.payloadJson());
    entity.setStatementSql(step.statementSql());
    entity.setStatementChecksum(step.statementChecksum());
    entity.setStatus(SchemaMigrationStepStatus.PENDING);
    entity.setAttempts(0);
    entity.setCreatedBy(actor.actorId());
    entity.setUpdatedBy(actor.actorId());
    return entity;
  }

  private static void validatePersistedSteps(
      final List<SchemaMigrationStepEntity> persisted,
      final List<PlannedSchemaStep> planned
  ) {
    if (persisted.size() != planned.size()) {
      throw drift("Persisted migration plan has a different number of steps");
    }
    for (int index = 0; index < persisted.size(); index++) {
      SchemaMigrationStepEntity existing = persisted.get(index);
      PlannedSchemaStep target = planned.get(index);
      if (existing.getSequence() != target.sequence()
          || existing.getOperation() != target.operation()
          || !existing.getStatementChecksum().equals(target.statementChecksum())) {
        throw drift("Persisted migration plan no longer matches the target schema");
      }
    }
  }

  private PhysicalModelEntity requireLockedModel(final String tenantId, final String modelId) {
    return modelRepository.findActiveByIdForUpdate(tenantId, modelId)
        .orElseThrow(() -> new DnaRuntimeException(
            DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
            "Physical model was not found in the active tenant"
        ));
  }

  private SchemaMigrationEntity requireLockedMigration(
      final String tenantId,
      final String migrationId
  ) {
    return migrationRepository.findActiveByIdForUpdate(tenantId, migrationId)
        .orElseThrow(() -> notFound("Schema migration was not found"));
  }

  private PhysicalModelBindingEntity requireBinding(
      final String tenantId,
      final String modelId
  ) {
    return bindingRepository.findActiveByModel(tenantId, modelId)
        .orElseThrow(() -> new DnaRuntimeException(
            DnaRuntimeErrorCode.PERSISTENCE_FAILED,
            "Physical model binding is inconsistent"
        ));
  }

  private PhysicalModelVersionEntity requireVersion(
      final String tenantId,
      final String modelId,
      final int version
  ) {
    return versionRepository.findActiveVersion(tenantId, modelId, version)
        .orElseThrow(() -> new DnaRuntimeException(
            DnaRuntimeErrorCode.PERSISTENCE_FAILED,
            "Physical model version is inconsistent"
        ));
  }

  private SchemaMigrationStepEntity requireStep(
      final String tenantId,
      final String migrationId,
      final int sequence
  ) {
    return stepRepository.findByMigration(tenantId, migrationId).stream()
        .filter(step -> step.getSequence() == sequence)
        .findFirst()
        .orElseThrow(() -> notFound("Schema migration step was not found"));
  }

  private static void requireExpectedVersion(
      final PhysicalModelEntity model,
      final long expectedVersion
  ) {
    if (model.getLockVersion() != expectedVersion) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_VERSION_CONFLICT,
          "The physical model was changed by another request"
      );
    }
  }

  private static void requireStatus(
      final SchemaMigrationEntity migration,
      final SchemaMigrationStatus status
  ) {
    if (migration.getStatus() != status) {
      throw conflict("Schema migration state changed concurrently");
    }
  }

  private static void requireExecutionToken(
      final SchemaMigrationEntity migration,
      final String executionToken
  ) {
    if (!Objects.equals(migration.getExecutionToken(), executionToken)) {
      throw conflict("Schema publication execution was fenced by another request");
    }
  }

  private static void requireModelStatus(
      final PhysicalModelEntity model,
      final PhysicalModelStatus status
  ) {
    if (model.getStatus() != status) {
      throw conflict("Physical model state changed concurrently");
    }
  }

  private static void transition(
      final PhysicalModelEntity model,
      final PhysicalModelStatus target,
      final String actorId
  ) {
    PhysicalModelTransitions.requireTransition(model.getStatus(), target);
    model.setStatus(target);
    model.setUpdatedBy(actorId);
  }

  private static void touch(final SchemaMigrationEntity migration, final String actorId) {
    migration.setUpdatedAt(Instant.now());
    migration.setUpdatedBy(actorId);
  }

  private static DnaRuntimeAuditEvent audit(
      final DnaRuntimeAuditEventType type,
      final DnaRuntimeAuditOutcome outcome,
      final DnaRuntimeErrorCode errorCode,
      final PhysicalModelEntity model,
      final PhysicalModelBindingEntity binding,
      final SchemaMigrationEntity migration,
      final RuntimeActor actor
  ) {
    return new DnaRuntimeAuditEvent(
        UUID.randomUUID().toString(),
        type,
        Instant.now(),
        actor.tenantId(),
        actor.actorId(),
        model.getId(),
        model.getAppId(),
        model.getFormId(),
        binding.getDataSourceId(),
        migration.getTargetVersion(),
        null,
        Math.max(0L, Duration.between(migration.getStartedAt(), Instant.now()).toMillis()),
        outcome,
        errorCode
    );
  }

  private static DnaRuntimeException notFound(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_MIGRATION_NOT_FOUND, message);
  }

  private static DnaRuntimeException conflict(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT, message);
  }

  private static DnaRuntimeException drift(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED, message);
  }

  /** Result of an idempotent publication claim. */
  public record PublicationClaim(boolean execute, PublicationWork work) {
  }

  /** Detached trusted work context used outside the control-plane transaction. */
  public record PublicationWork(
      String migrationId,
      String executionToken,
      String physicalModelId,
      String displayName,
      String appId,
      String formId,
      int targetVersion,
      PhysicalSchemaTarget target,
      PhysicalSchemaDefinition currentSchema,
      PhysicalSchemaDefinition schema
  ) {
  }

  /** Minimal persisted step identity used to bind an in-memory plan to durable rows. */
  public record PersistedStep(
      String stepId,
      int sequence,
      String operation,
      String statementChecksum,
      SchemaMigrationStepStatus status
  ) {
  }

  /** Detached read-only input for schema plan previews. */
  public record PlanningWork(
      String physicalModelId,
      String displayName,
      int targetVersion,
      PhysicalSchemaTarget target,
      PhysicalSchemaDefinition currentSchema,
      PhysicalSchemaDefinition schema
  ) {
  }

}
