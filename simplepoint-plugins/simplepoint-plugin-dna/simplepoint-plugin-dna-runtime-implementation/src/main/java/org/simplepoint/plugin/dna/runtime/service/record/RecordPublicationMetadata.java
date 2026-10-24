package org.simplepoint.plugin.dna.runtime.service.record;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Component;

/** Resolves immutable execution metadata while the caller holds every affected control model lock. */
@Component
public class RecordPublicationMetadata {
  private final EntityManager em;
  private final RuntimeRecordModelResolver models;
  private final PhysicalSchemaCodec schemas;

  /** Resource projections are read as scalar snapshots; resource locks must precede model locks. */
  public RecordPublicationMetadata(final EntityManager em, final RuntimeRecordModelResolver models, final PhysicalSchemaCodec schemas) {
    this.em = em;
    this.models = models;
    this.schemas = schemas;
  }

  Snapshot resolve(final RuntimeActor actor, final RecordWritePolicyPublicationCommand command,
      final Map<String, PhysicalModelEntity> locked) {
    if (!em.isJoinedToTransaction() || command.calculation() == null) {
      throw new IllegalStateException("Calculation metadata requires a joined control transaction and explicit definition");
    }
    var references = new LinkedHashMap<String, RecordSchemaReference>();
    references.put(command.physicalModelId(), command.schemaReference());
    command.calculation().details().values().forEach(scope -> references.put(scope.physicalModelId(), scope.schemaReference()));
    if (!locked.keySet().equals(references.keySet())) {
      throw invalid("Publication locks do not match its complete model scope");
    }
    for (var entry : locked.entrySet()) {
      var model = entry.getValue();
      if (!em.contains(model) || em.getLockMode(model) != LockModeType.PESSIMISTIC_WRITE
          || !entry.getKey().equals(model.getId()) || !actor.tenantId().equals(model.getTenantId())
          || model.getDeletedAt() != null || model.getStatus() != PhysicalModelStatus.ACTIVE || model.getActiveVersion() == null) {
        throw invalid("Publication metadata requires live tenant model write locks");
      }
    }
    var resolved = new LinkedHashMap<String, RecordPublicationPatchCompiler.Resolved>();
    var bindings = new LinkedHashMap<String, RecordPublicationIntent.Binding>();
    var resources = new LinkedHashMap<String, Map<String, RecordPublicationIntent.Resource>>();
    references.forEach((id, reference) -> {
      var model = models.resolve(actor, id, reference);
      if (!command.appId().equals(model.appId()) || id.equals(command.physicalModelId()) && !command.formId().equals(model.formId())) {
        throw invalid("Publication models do not belong to the declared owner");
      }
      var associations = resources(actor.tenantId(), id);
      resources.put(id, associations);
      resolved.put(id, new RecordPublicationPatchCompiler.Resolved(model, 0, associations));
      bindings.put(id, new RecordPublicationIntent.Binding(new RecordSchemaReference(model.activeVersion(),
          schemas.encode(model.activeSchema()).checksum()), reference, model.target().metadataTarget(), 0));
    });
    var history = new LinkedHashMap<RecordSchemaReference, RuntimeRecordModel>();
    command.checks().values().forEach(query -> history.computeIfAbsent(query.schemaReference(),
        reference -> models.resolve(actor, command.physicalModelId(), reference)));
    command.valueChecks().values().forEach(query -> history.computeIfAbsent(query.schemaReference(),
        reference -> models.resolve(actor, command.physicalModelId(), reference)));
    return new Snapshot(Map.copyOf(resolved), Map.copyOf(bindings), Map.copyOf(resources), Map.copyOf(history));
  }

  private Map<String, RecordPublicationIntent.Resource> resources(final String tenant, final String model) {
    // Scalar projection avoids stale persistence-context entities. Every association mutation takes
    // this model's write lock; no resource lock is acquired after it, including missing projections.
    var rows = em.createQuery("""
        select b.id, b.resourceCode, p.id, p.ownerKey, p.generation, p.formallyOffline, p.physicalModelId
        from PhysicalModelResourceBindingEntity b
        left join PhysicalResourceProjectionEntity p on p.tenantId = b.tenantId and p.resourceCode = b.resourceCode
        where b.tenantId = :tenant and b.physicalModelId = :model and b.deletedAt is null
        order by b.resourceCode, b.id
        """, Object[].class).setParameter("tenant", tenant).setParameter("model", model).setMaxResults(1025).getResultList();
    if (rows.size() > 1024) {
      throw invalid("Publication resource snapshot exceeds its bounded storage budget");
    }
    var result = new LinkedHashMap<String, RecordPublicationIntent.Resource>();
    for (var row : rows) {
      if (Boolean.TRUE.equals(row[5]) || row[3] != null && !model.equals(row[6])) {
        throw new DnaRuntimeException(DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED,
            "An active publication resource association is offline or superseded");
      }
      var projection = row[2] == null ? null : new RecordPublicationIntent.Projection(
          (String) row[2], (String) row[3], ((Number) row[4]).longValue(), (String) row[6]);
      if (result.put((String) row[1], new RecordPublicationIntent.Resource((String) row[0], projection)) != null) {
        throw invalid("Publication resource association is not unique");
      }
    }
    return Map.copyOf(result);
  }

  private static DnaRuntimeException invalid(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE, message);
  }

  record Snapshot(Map<String, RecordPublicationPatchCompiler.Resolved> models,
      Map<String, RecordPublicationIntent.Binding> bindings,
      Map<String, Map<String, RecordPublicationIntent.Resource>> resources,
      Map<RecordSchemaReference, RuntimeRecordModel> historical) {}
}
