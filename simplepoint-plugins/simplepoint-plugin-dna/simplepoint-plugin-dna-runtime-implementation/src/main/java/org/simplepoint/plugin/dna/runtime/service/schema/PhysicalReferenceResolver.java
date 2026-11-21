package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.springframework.stereotype.Component;

/** Resolves storage only; this is not a record authorization or write-admission token. */
@Component
public final class PhysicalReferenceResolver {
  private final PhysicalModelRepository models;
  private final PhysicalModelBindingRepository bindings;
  private final PhysicalModelVersionRepository versions;
  private final PhysicalSchemaCodec schemas;

  /** Uses tenant-scoped model bindings and immutable logical versions. */
  public PhysicalReferenceResolver(
      PhysicalModelRepository models,
      PhysicalModelBindingRepository bindings,
      PhysicalModelVersionRepository versions,
      PhysicalSchemaCodec schemas) {
    this.models = models;
    this.bindings = bindings;
    this.versions = versions;
    this.schemas = schemas;
  }

  /** Resolves only currently active independent targets for a new publication. */
  public PhysicalSchemaTarget resolve(
      String tenant, PhysicalSchemaTarget source, PhysicalSchemaDefinition schema) {
    return resolveTargets(tenant, source, schema, true);
  }

  /**
   * Retained storage identity for integrity checks, never permission to create a new business link.
   */
  public PhysicalSchemaTarget resolveStored(
      String tenant, PhysicalSchemaTarget source, PhysicalSchemaDefinition schema) {
    return resolveTargets(tenant, source, schema, false);
  }

  private PhysicalSchemaTarget resolveTargets(
      String tenant,
      PhysicalSchemaTarget source,
      PhysicalSchemaDefinition schema,
      boolean requireActive) {
    Map<String, RuntimeMetadataTarget> targets = new LinkedHashMap<>();
    Map<String, PhysicalSchemaDefinition> targetSchemas = new LinkedHashMap<>();
    for (var reference : schema.references()) {
      if (targets.containsKey(reference.targetModelId())) {
        ReferenceFieldDependencies.requireCompatible(
            reference, targetSchemas.get(reference.targetModelId()));
        continue;
      }
      RuntimeMetadataTarget target;
      PhysicalSchemaDefinition targetSchema;
      if (reference.targetModelId().equals(source.physicalModelId())) {
        target = source.metadataTarget();
        targetSchema = schema;
      } else {
        var model =
            models
                .findActiveById(tenant, reference.targetModelId())
                .orElseThrow(PhysicalReferenceResolver::unavailable);
        if ((requireActive && model.getStatus() != PhysicalModelStatus.ACTIVE)
            || model.getActiveVersion() == null) {
          throw unavailable();
        }
        var binding =
            bindings
                .findActiveByModel(tenant, model.getId())
                .orElseThrow(PhysicalReferenceResolver::unavailable);
        if (source.ownership() != null
            && !source.ownership().namespaceId().equals(binding.getPhysicalNamespaceId())) {
          throw unavailable();
        }
        var version =
            versions
                .findActiveVersion(tenant, model.getId(), model.getActiveVersion())
                .orElseThrow(PhysicalReferenceResolver::unavailable);
        targetSchema = schemas.decode(version.getSchemaJson());
        target =
            new RuntimeMetadataTarget(
                binding.getDataSourceId(),
                binding.getCatalogName(),
                binding.getSchemaName(),
                binding.getTableName());
      }
      var own = source.metadataTarget();
      if (targetSchema.owner() != null
          || targetSchema.collection() != null
          || !Boolean.TRUE.equals(targetSchema.tenantRecordIdentity())
          || !own.dataSourceId().equals(target.dataSourceId())
          || !own.catalogName().equals(target.catalogName())
          || !own.schemaName().equals(target.schemaName())) {
        throw unavailable();
      }
      targets.put(reference.targetModelId(), target);
      targetSchemas.put(reference.targetModelId(), targetSchema);
      ReferenceFieldDependencies.requireCompatible(reference, targetSchema);
    }
    return new PhysicalSchemaTarget(
        source.physicalModelId(), source.metadataTarget(), targets, source.ownership());
  }

  private static DnaRuntimeException unavailable() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.SCHEMA_INVALID,
        "Reference target requires independent tenant-owned published storage with an identity key"
            + " in the same registered namespace");
  }
}
