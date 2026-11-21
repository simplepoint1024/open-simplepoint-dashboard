package org.simplepoint.plugin.dna.runtime.service.record;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.springframework.stereotype.Component;

/**
 * Control-plane model locks serialize metadata changes with publication batches. The target journal
 * remains the durable maintenance fact; there is no second independently committed ownership flag.
 * Callers acquire resource projection locks first, then model locks in stable order, then target
 * locks.
 */
@Component
public class RecordMaintenanceControl {
  private final EntityManager em;
  private final PhysicalModelRepository models;
  private final PhysicalModelBindingRepository bindings;
  private final PhysicalModelVersionRepository versions;
  private final PhysicalSchemaCodec schemas;
  private final RuntimeCalciteRecordBackend backend;

  /**
   * Uses trusted metadata and the same target admission implementation as ordinary Record reads.
   */
  public RecordMaintenanceControl(
      final EntityManager em,
      final PhysicalModelRepository models,
      final PhysicalModelBindingRepository bindings,
      final PhysicalModelVersionRepository versions,
      final PhysicalSchemaCodec schemas,
      final RuntimeCalciteRecordBackend backend) {
    this.em = em;
    this.models = models;
    this.bindings = bindings;
    this.versions = versions;
    this.schemas = schemas;
    this.backend = backend;
  }

  /**
   * All affected old/new model associations must be locked before changing a resource projection.
   */
  public Map<String, PhysicalModelEntity> lockAvailable(
      final String tenant, final Collection<String> modelIds) {
    var locked = lockModels(tenant, modelIds);
    locked.values().forEach(model -> requireAvailable(tenant, model));
    return locked;
  }

  /** The caller keeps this model write lock until its surrounding control transaction completes. */
  public void requireAvailable(final String tenant, final PhysicalModelEntity model) {
    requireLocked(tenant, model);
    var version =
        model.getActiveVersion() == null ? model.getLatestVersion() : model.getActiveVersion();
    var stored =
        versions
            .findActiveVersion(tenant, model.getId(), version)
            .orElseThrow(RecordMaintenanceControl::inconsistent);
    var owner = schemas.decode(stored.getSchemaJson()).owner();
    PhysicalModelEntity root =
        owner == null
            ? model
            : models
                .findActiveById(tenant, owner.parentModelId())
                .orElseThrow(RecordMaintenanceControl::inconsistent);
    requireLocked(tenant, root);
    // An unpublished root has never admitted records or a calculation publication.
    if (root.getActiveVersion() == null) {
      return;
    }
    var active =
        versions
            .findActiveVersion(tenant, root.getId(), root.getActiveVersion())
            .orElseThrow(RecordMaintenanceControl::inconsistent);
    var schema = schemas.decode(active.getSchemaJson());
    if (schema.owner() != null || !root.getAppId().equals(model.getAppId())) {
      throw inconsistent();
    }
    var binding =
        bindings
            .findActiveByModel(tenant, root.getId())
            .orElseThrow(RecordMaintenanceControl::inconsistent);
    var resolved =
        new RuntimeRecordModel(
            root.getId(),
            root.getAppId(),
            root.getFormId(),
            root.getActiveVersion(),
            schema,
            new PhysicalSchemaTarget(
                    root.getId(),
                    new RuntimeMetadataTarget(
                        binding.getDataSourceId(),
                        binding.getCatalogName(),
                        binding.getSchemaName(),
                        binding.getTableName()))
                .withOwnership(binding.getPhysicalNamespaceId(), tenant));
    backend.requireMaintenanceAvailable(resolved, tenant);
  }

  /** Locks affected models and their immutable owners; publication may inspect its own journal. */
  public Map<String, PhysicalModelEntity> lockModels(
      final String tenant, final Collection<String> modelIds) {
    if (!em.isJoinedToTransaction()
        || tenant == null
        || tenant.isBlank()
        || modelIds == null
        || modelIds.isEmpty()) {
      throw new IllegalStateException(
          "Maintenance control requires an active tenant-scoped control transaction");
    }
    var affected = new java.util.TreeSet<>(modelIds);
    for (String id : modelIds) {
      var model =
          models
              .findActiveById(tenant, id)
              .orElseThrow(
                  () ->
                      new DnaRuntimeException(
                          DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
                          "Maintenance model was not found"));
      int number =
          model.getActiveVersion() == null ? model.getLatestVersion() : model.getActiveVersion();
      var version =
          versions
              .findActiveVersion(tenant, id, number)
              .orElseThrow(RecordMaintenanceControl::inconsistent);
      var owner = schemas.decode(version.getSchemaJson()).owner();
      // Owner relations are immutable. Discover them before locking, then refresh every entity
      // under the common sorted lock order; never acquire an owner after locking its child.
      if (owner != null) {
        affected.add(owner.parentModelId());
      }
    }
    var result = new LinkedHashMap<String, PhysicalModelEntity>();
    for (String id : affected) {
      var model =
          models
              .findActiveById(tenant, id)
              .orElseThrow(
                  () ->
                      new DnaRuntimeException(
                          DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
                          "Maintenance model was not found"));
      // Acquire the lock through refresh itself. A locking entity query first compares the old
      // persistence-context version and can fail before refreshing a model changed while waiting.
      em.refresh(model, LockModeType.PESSIMISTIC_WRITE);
      requireLocked(tenant, model);
      result.put(id, model);
    }
    return java.util.Collections.unmodifiableMap(result);
  }

  private void requireLocked(final String tenant, final PhysicalModelEntity model) {
    if (!em.isJoinedToTransaction()
        || model == null
        || !tenant.equals(model.getTenantId())
        || !em.contains(model)
        || em.getLockMode(model) != LockModeType.PESSIMISTIC_WRITE
        || model.getDeletedAt() != null) {
      throw new IllegalStateException(
          "Maintenance inspection requires the tenant model's control write lock");
    }
  }

  private static DnaRuntimeException inconsistent() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Maintenance model metadata is inconsistent");
  }
}
