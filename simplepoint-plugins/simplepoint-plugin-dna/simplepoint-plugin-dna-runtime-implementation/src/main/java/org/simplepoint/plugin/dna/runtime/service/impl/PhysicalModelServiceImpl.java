package org.simplepoint.plugin.dna.runtime.service.impl;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEvent;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditOutcome;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditPublisher;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingTarget;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelRegistrationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelTransitions;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalPlacementRequest;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalBindingTargetResolver;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalModelService;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalTableNamingStrategy;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec.EncodedSchema;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaValidator;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional registry for physical model identities, bindings, and schema versions. */
@Service
public class PhysicalModelServiceImpl implements PhysicalModelService {

  private static final Set<PhysicalModelStatus> VERSIONABLE_STATES = Set.of(
      PhysicalModelStatus.DRAFT,
      PhysicalModelStatus.ACTIVE,
      PhysicalModelStatus.FAILED
  );

  private final PhysicalModelRepository modelRepository;
  private final PhysicalModelBindingRepository bindingRepository;
  private final PhysicalModelVersionRepository versionRepository;
  private final PhysicalBindingTargetResolver bindingTargetResolver;
  private final PhysicalTableNamingStrategy tableNamingStrategy;
  private final PhysicalSchemaCodec schemaCodec;
  private final PhysicalSchemaValidator schemaValidator;
  private final RuntimeRequestContext requestContext;
  private final DnaRuntimeAuditPublisher auditPublisher;

  /** Creates the transactional registry from replaceable persistence and policy ports. */
  public PhysicalModelServiceImpl(
      final PhysicalModelRepository modelRepository,
      final PhysicalModelBindingRepository bindingRepository,
      final PhysicalModelVersionRepository versionRepository,
      final PhysicalBindingTargetResolver bindingTargetResolver,
      final PhysicalTableNamingStrategy tableNamingStrategy,
      final PhysicalSchemaCodec schemaCodec,
      final PhysicalSchemaValidator schemaValidator,
      final RuntimeRequestContext requestContext,
      final DnaRuntimeAuditPublisher auditPublisher
  ) {
    this.modelRepository = modelRepository;
    this.bindingRepository = bindingRepository;
    this.versionRepository = versionRepository;
    this.bindingTargetResolver = bindingTargetResolver;
    this.tableNamingStrategy = tableNamingStrategy;
    this.schemaCodec = schemaCodec;
    this.schemaValidator = schemaValidator;
    this.requestContext = requestContext;
    this.auditPublisher = auditPublisher;
  }

  @Override
  @Transactional
  public PhysicalModelView register(final PhysicalModelRegistrationCommand command) {
    long startedAt = System.nanoTime();
    if (command == null) {
      throw invalidArgument("Registration command is required");
    }
    RuntimeActor actor = requestContext.requireActor();
    if (modelExists(
        actor.tenantId(),
        command.appId(),
        command.formId()
    )) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_ALREADY_EXISTS,
          "A physical model already exists for this form"
      );
    }

    PhysicalBindingTarget target = bindingTargetResolver.resolve(
        new PhysicalPlacementRequest(
            actor.tenantId(),
            command.appId(),
            command.formId(),
            command.placementKey()
        )
    );
    String modelId = UUID.randomUUID().toString();
    String tableName = tableNamingStrategy.tableName(modelId);
    String catalogName = emptyIfNull(target.catalogName());
    String schemaName = emptyIfNull(target.schemaName());
    if (bindingExists(
        target.dataSourceId(),
        catalogName,
        schemaName,
        tableName
    )) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_BINDING_CONFLICT,
          "The generated physical target is already bound"
      );
    }

    schemaValidator.validate(command.initialSchema());
    EncodedSchema encoded = schemaCodec.encode(command.initialSchema());
    PhysicalModelEntity model = newModel(modelId, command, actor);
    PhysicalModelBindingEntity binding = newBinding(
        modelId,
        tableName,
        catalogName,
        schemaName,
        target,
        actor
    );
    PhysicalModelVersionEntity version = newVersion(
        modelId,
        1,
        command.initialSchema().contractVersion(),
        encoded,
        actor
    );

    try {
      modelRepository.save(model);
      bindingRepository.save(binding);
      versionRepository.save(version);
      auditPublisher.publish(successEvent(
          DnaRuntimeAuditEventType.PHYSICAL_MODEL_REGISTERED,
          model,
          actor,
          target.dataSourceId(),
          1,
          elapsedMillis(startedAt)
      ));
      modelRepository.flush();
      return toView(model, version);
    } catch (DataIntegrityViolationException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_REGISTRATION_CONFLICT,
          "Physical model registration conflicts with an existing model",
          exception
      );
    } catch (DataAccessException exception) {
      throw persistenceFailure("Physical model registration failed", exception);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public PhysicalModelView get(final String physicalModelId) {
    String modelId = requireModelId(physicalModelId);
    RuntimeActor actor = requestContext.requireActor();
    PhysicalModelEntity model = requireModel(actor.tenantId(), modelId, false);
    requireBinding(actor.tenantId(), modelId);
    PhysicalModelVersionEntity version = requireVersion(
        actor.tenantId(),
        modelId,
        model.getLatestVersion()
    );
    return toView(model, version);
  }

  @Override
  @Transactional
  public PhysicalModelVersionView createVersion(
      final String physicalModelId,
      final PhysicalModelVersionCommand command
  ) {
    long startedAt = System.nanoTime();
    String modelId = requireModelId(physicalModelId);
    if (command == null) {
      throw invalidArgument("Version command is required");
    }
    RuntimeActor actor = requestContext.requireActor();
    PhysicalModelEntity model = requireModel(actor.tenantId(), modelId, true);
    PhysicalModelBindingEntity binding = requireBinding(actor.tenantId(), modelId);
    if (model.getLockVersion() != command.expectedLockVersion()) {
      throw versionConflict(modelId);
    }
    if (!VERSIONABLE_STATES.contains(model.getStatus())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_STATE_CONFLICT,
          "A schema version cannot be created while the model is " + model.getStatus()
      );
    }
    if (model.getStatus() != PhysicalModelStatus.DRAFT) {
      PhysicalModelTransitions.requireTransition(model.getStatus(), PhysicalModelStatus.DRAFT);
      model.setStatus(PhysicalModelStatus.DRAFT);
    }

    int nextVersion = model.getLatestVersion() + 1;
    schemaValidator.validate(command.schema());
    EncodedSchema encoded = schemaCodec.encode(command.schema());
    PhysicalModelVersionEntity version = newVersion(
        modelId,
        nextVersion,
        command.schema().contractVersion(),
        encoded,
        actor
    );
    model.setLatestVersion(nextVersion);
    model.setUpdatedBy(actor.actorId());

    try {
      versionRepository.save(version);
      modelRepository.save(model);
      auditPublisher.publish(successEvent(
          DnaRuntimeAuditEventType.PHYSICAL_MODEL_VERSION_CREATED,
          model,
          actor,
          binding.getDataSourceId(),
          nextVersion,
          elapsedMillis(startedAt)
      ));
      modelRepository.flush();
      return toVersionView(version);
    } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_VERSION_CONFLICT,
          "The physical model was changed by another request",
          exception
      );
    } catch (DataAccessException exception) {
      throw persistenceFailure("Physical model version creation failed", exception);
    }
  }

  private PhysicalModelEntity requireModel(
      final String tenantId,
      final String modelId,
      final boolean forUpdate
  ) {
    try {
      return (forUpdate
          ? modelRepository.findActiveByIdForUpdate(tenantId, modelId)
          : modelRepository.findActiveById(tenantId, modelId))
          .orElseThrow(() -> new DnaRuntimeException(
              DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
              "Physical model was not found in the active tenant"
          ));
    } catch (DataAccessException exception) {
      throw persistenceFailure("Physical model lookup failed", exception);
    }
  }

  private PhysicalModelBindingEntity requireBinding(
      final String tenantId,
      final String modelId
  ) {
    try {
      return bindingRepository.findActiveByModel(tenantId, modelId)
          .orElseThrow(() -> inconsistentModel(modelId, "binding"));
    } catch (DataAccessException exception) {
      throw persistenceFailure("Physical model binding lookup failed", exception);
    }
  }

  private PhysicalModelVersionEntity requireVersion(
      final String tenantId,
      final String modelId,
      final int versionNumber
  ) {
    try {
      return versionRepository.findActiveVersion(tenantId, modelId, versionNumber)
          .orElseThrow(() -> inconsistentModel(modelId, "latest version"));
    } catch (DataAccessException exception) {
      throw persistenceFailure("Physical model version lookup failed", exception);
    }
  }

  private boolean modelExists(
      final String tenantId,
      final String appId,
      final String formId
  ) {
    try {
      return modelRepository.existsActiveByForm(tenantId, appId, formId);
    } catch (DataAccessException exception) {
      throw persistenceFailure("Physical model uniqueness check failed", exception);
    }
  }

  private boolean bindingExists(
      final String dataSourceId,
      final String catalogName,
      final String schemaName,
      final String tableName
  ) {
    try {
      return bindingRepository.existsActiveTarget(
          dataSourceId,
          catalogName,
          schemaName,
          tableName
      );
    } catch (DataAccessException exception) {
      throw persistenceFailure("Physical binding uniqueness check failed", exception);
    }
  }

  private PhysicalModelEntity newModel(
      final String modelId,
      final PhysicalModelRegistrationCommand command,
      final RuntimeActor actor
  ) {
    PhysicalModelEntity entity = new PhysicalModelEntity();
    entity.setId(modelId);
    entity.setTenantId(actor.tenantId());
    entity.setAppId(command.appId());
    entity.setFormId(command.formId());
    entity.setDisplayName(command.displayName());
    entity.setStatus(PhysicalModelStatus.DRAFT);
    entity.setLatestVersion(1);
    entity.setCreatedBy(actor.actorId());
    entity.setUpdatedBy(actor.actorId());
    return entity;
  }

  private PhysicalModelBindingEntity newBinding(
      final String modelId,
      final String tableName,
      final String catalogName,
      final String schemaName,
      final PhysicalBindingTarget target,
      final RuntimeActor actor
  ) {
    PhysicalModelBindingEntity entity = new PhysicalModelBindingEntity();
    entity.setId(UUID.randomUUID().toString());
    entity.setTenantId(actor.tenantId());
    entity.setPhysicalModelId(modelId);
    entity.setDataSourceId(target.dataSourceId());
    entity.setCatalogName(catalogName);
    entity.setSchemaName(schemaName);
    entity.setTableName(tableName);
    entity.setIsolationMode(target.isolationMode());
    entity.setCreatedBy(actor.actorId());
    entity.setUpdatedBy(actor.actorId());
    return entity;
  }

  private PhysicalModelVersionEntity newVersion(
      final String modelId,
      final int versionNumber,
      final String contractVersion,
      final EncodedSchema encoded,
      final RuntimeActor actor
  ) {
    PhysicalModelVersionEntity entity = new PhysicalModelVersionEntity();
    entity.setId(UUID.randomUUID().toString());
    entity.setTenantId(actor.tenantId());
    entity.setPhysicalModelId(modelId);
    entity.setVersionNumber(versionNumber);
    entity.setContractVersion(contractVersion);
    entity.setSchemaJson(encoded.json());
    entity.setSchemaChecksum(encoded.checksum());
    entity.setCreatedBy(actor.actorId());
    entity.setUpdatedBy(actor.actorId());
    return entity;
  }

  private PhysicalModelView toView(
      final PhysicalModelEntity model,
      final PhysicalModelVersionEntity version
  ) {
    return new PhysicalModelView(
        model.getId(),
        model.getAppId(),
        model.getFormId(),
        model.getDisplayName(),
        model.getStatus(),
        model.getLatestVersion(),
        model.getActiveVersion(),
        model.getLockVersion(),
        toVersionView(version),
        model.getCreatedAt(),
        model.getUpdatedAt()
    );
  }

  private PhysicalModelVersionView toVersionView(
      final PhysicalModelVersionEntity version
  ) {
    return new PhysicalModelVersionView(
        version.getId(),
        version.getPhysicalModelId(),
        version.getVersionNumber(),
        schemaCodec.decode(version.getSchemaJson()),
        version.getSchemaChecksum(),
        version.getCreatedBy(),
        version.getCreatedAt()
    );
  }

  private static DnaRuntimeAuditEvent successEvent(
      final DnaRuntimeAuditEventType eventType,
      final PhysicalModelEntity model,
      final RuntimeActor actor,
      final String dataSourceId,
      final int modelVersion,
      final long durationMillis
  ) {
    return new DnaRuntimeAuditEvent(
        UUID.randomUUID().toString(),
        eventType,
        Instant.now(),
        actor.tenantId(),
        actor.actorId(),
        model.getId(),
        model.getAppId(),
        model.getFormId(),
        dataSourceId,
        modelVersion,
        null,
        durationMillis,
        DnaRuntimeAuditOutcome.SUCCEEDED,
        null
    );
  }

  private static String requireModelId(final String modelId) {
    if (modelId == null || modelId.isBlank() || modelId.length() > 36) {
      throw invalidArgument("A valid physical model id is required");
    }
    return modelId.trim();
  }

  private static long elapsedMillis(final long startedAt) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
  }

  private static String emptyIfNull(final String value) {
    return value == null ? "" : value;
  }

  private static DnaRuntimeException invalidArgument(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.INVALID_ARGUMENT, message);
  }

  private static DnaRuntimeException versionConflict(final String modelId) {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PHYSICAL_MODEL_VERSION_CONFLICT,
        "Physical model lock version does not match: " + modelId
    );
  }

  private static DnaRuntimeException inconsistentModel(
      final String modelId,
      final String missingPart
  ) {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Physical model " + modelId + " is missing its " + missingPart
    );
  }

  private static DnaRuntimeException persistenceFailure(
      final String message,
      final DataAccessException cause
  ) {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        message,
        cause
    );
  }
}
