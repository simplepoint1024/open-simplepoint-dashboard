package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalReferenceResolver;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Resolves only active tenant-owned models into detached record execution metadata. */
@Component
public class RuntimeRecordModelResolver {

  private final PhysicalModelRepository modelRepository;
  private final PhysicalModelBindingRepository bindingRepository;
  private final PhysicalModelVersionRepository versionRepository;
  private final PhysicalSchemaCodec schemaCodec;
  private final PhysicalReferenceResolver references;

  /** Creates the active model resolver. */
  public RuntimeRecordModelResolver(
      final PhysicalModelRepository modelRepository,
      final PhysicalModelBindingRepository bindingRepository,
      final PhysicalModelVersionRepository versionRepository,
      final PhysicalSchemaCodec schemaCodec,
      final PhysicalReferenceResolver references) {
    this.modelRepository = modelRepository;
    this.bindingRepository = bindingRepository;
    this.versionRepository = versionRepository;
    this.schemaCodec = schemaCodec;
    this.references = references;
  }

  /** Resolves one active model without exposing placement outside the implementation. */
  @Transactional(readOnly = true)
  RuntimeRecordModel resolve(
      final RuntimeActor actor, final String modelId, final RecordSchemaReference schemaReference) {
    return resolveMetadata(actor, modelId, schemaReference, true);
  }

  /** Resolves the current target only from a trusted installed relation, not browser metadata. */
  @Transactional(readOnly = true)
  RuntimeRecordModel resolveCurrent(final RuntimeActor actor, final String modelId) {
    return resolveMetadata(actor, modelId, null, true);
  }

  /**
   * Cancellation authenticates the historical reference but cannot require it to write current
   * business fields.
   */
  @Transactional(readOnly = true)
  RuntimeRecordModel resolveForCancellation(
      final RuntimeActor actor, final String modelId, final RecordSchemaReference schemaReference) {
    return resolveMetadata(actor, modelId, schemaReference, false);
  }

  private RuntimeRecordModel resolveMetadata(
      final RuntimeActor actor,
      final String modelId,
      final RecordSchemaReference schemaReference,
      final boolean requireWriteCompatibility) {
    PhysicalModelEntity model =
        modelRepository
            .findActiveById(actor.tenantId(), modelId)
            .orElseThrow(RuntimeRecordModelResolver::notFound);
    if (model.getStatus() != PhysicalModelStatus.ACTIVE || model.getActiveVersion() == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_ACTIVE,
          "Physical model is not active for record operations");
    }
    PhysicalModelBindingEntity binding =
        bindingRepository
            .findActiveByModel(actor.tenantId(), model.getId())
            .orElseThrow(RuntimeRecordModelResolver::inconsistent);
    PhysicalModelVersionEntity activeVersion =
        versionRepository
            .findActiveVersion(actor.tenantId(), model.getId(), model.getActiveVersion())
            .orElseThrow(RuntimeRecordModelResolver::inconsistent);
    PhysicalModelVersionEntity requestedVersion =
        schemaReference == null || schemaReference.version() == model.getActiveVersion()
            ? activeVersion
            : versionRepository
                .findActiveVersion(actor.tenantId(), model.getId(), schemaReference.version())
                .orElseThrow(RuntimeRecordModelResolver::incompatible);
    if (schemaReference != null
        && !requestedVersion.getSchemaChecksum().equals(schemaReference.checksum())) {
      throw incompatible();
    }
    var activeSchema = schemaCodec.decode(activeVersion.getSchemaJson());
    var requestedSchema = schemaCodec.decode(requestedVersion.getSchemaJson());
    if (requireWriteCompatibility) {
      RecordSchemaCompatibility.requireCompatible(requestedSchema, activeSchema);
    } else if (!java.util.Objects.equals(requestedSchema.owner(), activeSchema.owner())
        || !java.util.Objects.equals(requestedSchema.collection(), activeSchema.collection())) {
      throw incompatible();
    }
    var target =
        new PhysicalSchemaTarget(
                model.getId(),
                new org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget(
                    binding.getDataSourceId(),
                    binding.getCatalogName(),
                    binding.getSchemaName(),
                    binding.getTableName()))
            .withOwnership(binding.getPhysicalNamespaceId(), actor.tenantId());
    if (!activeSchema.references().isEmpty()) {
      try {
        target = references.resolveStored(actor.tenantId(), target, activeSchema);
      } catch (DnaRuntimeException failure) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
            "The active reference storage binding is unavailable",
            failure);
      }
    }
    return new RuntimeRecordModel(
        model.getId(),
        model.getAppId(),
        model.getFormId(),
        model.getActiveVersion(),
        activeSchema,
        requestedVersion.getVersionNumber(),
        requestedSchema,
        target);
  }

  private static DnaRuntimeException notFound() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
        "Physical model was not found in the active tenant");
  }

  private static DnaRuntimeException inconsistent() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Active physical model metadata is inconsistent");
  }

  private static DnaRuntimeException incompatible() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
        "Requested record schema does not belong to the active physical model");
  }
}
