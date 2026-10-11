package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
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

  /** Creates the active model resolver. */
  public RuntimeRecordModelResolver(
      final PhysicalModelRepository modelRepository,
      final PhysicalModelBindingRepository bindingRepository,
      final PhysicalModelVersionRepository versionRepository,
      final PhysicalSchemaCodec schemaCodec
  ) {
    this.modelRepository = modelRepository;
    this.bindingRepository = bindingRepository;
    this.versionRepository = versionRepository;
    this.schemaCodec = schemaCodec;
  }

  /** Resolves one active model without exposing placement outside the implementation. */
  @Transactional(readOnly = true)
  RuntimeRecordModel resolve(final RuntimeActor actor, final String modelId) {
    PhysicalModelEntity model = modelRepository.findActiveById(actor.tenantId(), modelId)
        .orElseThrow(RuntimeRecordModelResolver::notFound);
    if (model.getStatus() != PhysicalModelStatus.ACTIVE || model.getActiveVersion() == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_ACTIVE,
          "Physical model is not active for record operations"
      );
    }
    PhysicalModelBindingEntity binding = bindingRepository.findActiveByModel(
        actor.tenantId(),
        model.getId()
    ).orElseThrow(RuntimeRecordModelResolver::inconsistent);
    PhysicalModelVersionEntity version = versionRepository.findActiveVersion(
        actor.tenantId(),
        model.getId(),
        model.getActiveVersion()
    ).orElseThrow(RuntimeRecordModelResolver::inconsistent);
    return new RuntimeRecordModel(
        model.getId(),
        model.getAppId(),
        model.getFormId(),
        model.getActiveVersion(),
        schemaCodec.decode(version.getSchemaJson()),
        new PhysicalSchemaTarget(
            model.getId(),
            new org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget(
                binding.getDataSourceId(),
                binding.getCatalogName(),
                binding.getSchemaName(),
                binding.getTableName()
            )
        )
    );
  }

  /** Resolves and preserves caller order for a bounded list of model ids. */
  List<RuntimeRecordModel> resolveAll(final RuntimeActor actor, final List<String> modelIds) {
    return modelIds.stream().map(modelId -> resolve(actor, modelId)).toList();
  }

  private static DnaRuntimeException notFound() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
        "Physical model was not found in the active tenant"
    );
  }

  private static DnaRuntimeException inconsistent() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Active physical model metadata is inconsistent"
    );
  }
}
