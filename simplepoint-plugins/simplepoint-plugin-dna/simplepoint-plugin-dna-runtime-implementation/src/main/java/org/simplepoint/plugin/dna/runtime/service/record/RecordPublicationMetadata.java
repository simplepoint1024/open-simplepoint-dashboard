package org.simplepoint.plugin.dna.runtime.service.record;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCollectionPreflightQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Component;

/**
 * Resolves immutable execution metadata while the caller holds every affected control model lock.
 */
@Component
public class RecordPublicationMetadata {
  private final EntityManager em;
  private final RuntimeRecordModelResolver models;
  private final PhysicalSchemaCodec schemas;

  /** Resource projections are read as scalar snapshots; resource locks must precede model locks. */
  public RecordPublicationMetadata(
      final EntityManager em,
      final RuntimeRecordModelResolver models,
      final PhysicalSchemaCodec schemas) {
    this.em = em;
    this.models = models;
    this.schemas = schemas;
  }

  RecordCollectionBinding resolveConversion(
      RuntimeActor actor, RecordReferenceCollectionPreflightQuery query) {
    var rule = query.destination();
    var source = models.resolve(actor, query.physicalModelId(), query.schemaReference());
    RecordManagementAccess.forOwner(actor, source, query.appId(), query.formId());
    var edge = models.resolve(actor, rule.edgeModelId(), rule.edgeSchema());
    var target =
        source.physicalModelId().equals(rule.targetModelId())
            ? source
            : models.resolveCurrent(actor, rule.targetModelId());
    var binding = RecordCollectionPublication.validate(rule, source, edge, target);
    requireEntrance(actor.tenantId(), target, rule.targetResourceCode());
    return binding;
  }

  Snapshot resolve(
      final RuntimeActor actor,
      final RecordWritePolicyPublicationCommand command,
      final Map<String, PhysicalModelEntity> locked) {
    return resolve(actor, command, locked, true);
  }

  /**
   * Cancellation only restores owned rows; an unavailable target must not prevent that recovery.
   */
  Snapshot resolve(
      final RuntimeActor actor,
      final RecordWritePolicyPublicationCommand command,
      final Map<String, PhysicalModelEntity> locked,
      final boolean validateReferences) {
    return resolve(actor, command, locked, validateReferences, true);
  }

  Snapshot resolve(
      RuntimeActor actor,
      RecordWritePolicyPublicationCommand command,
      Map<String, PhysicalModelEntity> locked,
      boolean validateReferences,
      boolean conversionStorage) {
    if (!em.isJoinedToTransaction() || command.calculation() == null) {
      throw new IllegalStateException(
          "Calculation metadata requires a joined control transaction and explicit definition");
    }
    var references = new LinkedHashMap<String, RecordSchemaReference>();
    references.put(command.physicalModelId(), command.schemaReference());
    command
        .calculation()
        .details()
        .values()
        .forEach(scope -> references.put(scope.physicalModelId(), scope.schemaReference()));
    var participants = new java.util.LinkedHashSet<>(references.keySet());
    if (validateReferences) {
      command
          .calculation()
          .referencesOrEmpty()
          .forEach(rule -> participants.add(rule.targetModelId()));
      command
          .calculation()
          .collectionsOrEmpty()
          .forEach(
              rule -> {
                participants.add(rule.edgeModelId());
                participants.add(rule.targetModelId());
              });
      command
          .retiringCollections()
          .forEach(
              rule -> {
                participants.add(rule.edgeModelId());
                participants.add(rule.targetModelId());
              });
    }
    var conversionRule = conversionStorage ? command.conversionDestination() : null;
    var rebindSource = conversionStorage ? command.rebindSource() : null;
    var rebindDestination = conversionStorage ? command.rebindDestination() : null;
    java.util.stream.Stream.of(conversionRule, rebindSource, rebindDestination)
        .filter(java.util.Objects::nonNull).forEach(rule -> {
          participants.add(rule.edgeModelId());
          participants.add(rule.targetModelId());
        });
    if (!locked.keySet().equals(participants)) {
      throw invalid("Publication locks do not match its complete model scope");
    }
    for (var entry : locked.entrySet()) {
      var model = entry.getValue();
      if (!em.contains(model)
          || em.getLockMode(model) != LockModeType.PESSIMISTIC_WRITE
          || !entry.getKey().equals(model.getId())
          || !actor.tenantId().equals(model.getTenantId())
          || model.getDeletedAt() != null
          || model.getStatus() != PhysicalModelStatus.ACTIVE
          || model.getActiveVersion() == null) {
        throw invalid("Publication metadata requires live tenant model write locks");
      }
    }
    var resolved = new LinkedHashMap<String, RecordPublicationPatchCompiler.Resolved>();
    var bindings = new LinkedHashMap<String, RecordPublicationIntent.Binding>();
    var resources = new LinkedHashMap<String, Map<String, RecordPublicationIntent.Resource>>();
    references.forEach(
        (id, reference) -> {
          var model = models.resolve(actor, id, reference);
          if (!command.appId().equals(model.appId())
              || id.equals(command.physicalModelId()) && !command.formId().equals(model.formId())) {
            throw invalid("Publication models do not belong to the declared owner");
          }
          var associations = resources(actor.tenantId(), id);
          resources.put(id, associations);
          resolved.put(id, new RecordPublicationPatchCompiler.Resolved(model, 0, associations));
          bindings.put(
              id,
              new RecordPublicationIntent.Binding(
                  new RecordSchemaReference(
                      model.activeVersion(), schemas.encode(model.activeSchema()).checksum()),
                  reference,
                  model.target().metadataTarget(),
                  0));
        });
    var root = resolved.get(command.physicalModelId()).model();
    var conversion =
        conversionRule == null ? null : transfer(actor, root, conversionRule, validateReferences);
    var rebind =
        rebindSource == null
            ? null
            : new Rebind(
                transfer(actor, root, rebindSource, false),
                transfer(actor, root, rebindDestination, validateReferences));
    Map<String, RecordPublicationIntent.Binding> collections = Map.of();
    var retiring = new LinkedHashMap<String, RuntimeRecordModel>();
    var retirementBindings = new LinkedHashMap<String, RecordPublicationIntent.Binding>();
    if (validateReferences) {
      validateReferences(actor, command, resolved);
      var destination = rebind == null ? conversion : rebind.destination();
      collections = validateCollections(actor, command, root, destination);
      for (var rule : command.retiringCollections()) {
        if (rebind != null && rule.relationId().equals(rebindSource.relationId())) {
          var source = rebind.source();
          RecordCollectionPublication.validate(
              rule, root, source.binding().edge(), source.binding().target());
          retiring.put(rule.relationId(), source.binding().edge());
          retirementBindings.put(rule.relationId(), source.edge());
          continue;
        }
        var edge = models.resolve(actor, rule.edgeModelId(), rule.edgeSchema());
        var target =
            rule.targetModelId().equals(root.physicalModelId())
                ? root
                : models.resolveCurrent(actor, rule.targetModelId());
        // The exact former resource may be offline. Retirement still validates the original
        // storage.
        RecordCollectionPublication.validate(rule, root, edge, target);
        retiring.put(rule.relationId(), edge);
        retirementBindings.put(rule.relationId(), collectionBinding(rule, edge));
      }
    }
    var history = new LinkedHashMap<RecordSchemaReference, RuntimeRecordModel>();
    command
        .checks()
        .values()
        .forEach(
            query ->
                history.computeIfAbsent(
                    query.schemaReference(),
                    reference -> models.resolve(actor, command.physicalModelId(), reference)));
    command
        .valueChecks()
        .values()
        .forEach(
            query ->
                history.computeIfAbsent(
                    query.schemaReference(),
                    reference -> models.resolve(actor, command.physicalModelId(), reference)));
    return new Snapshot(
        Map.copyOf(resolved),
        Map.copyOf(bindings),
        Map.copyOf(resources),
        Map.copyOf(history),
        collections,
        Map.copyOf(retiring),
        Map.copyOf(retirementBindings),
        conversion,
        rebind);
  }

  private Conversion transfer(
      RuntimeActor actor,
      RuntimeRecordModel source,
      org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule rule,
      boolean requireLiveEntrance) {
    var edge = models.resolve(actor, rule.edgeModelId(), rule.edgeSchema());
    var target =
        source.physicalModelId().equals(rule.targetModelId())
            ? source
            : models.resolveCurrent(actor, rule.targetModelId());
    var binding =
        requireLiveEntrance
            ? RecordCollectionPublication.validate(rule, source, edge, target)
            : new RecordCollectionBinding(edge, source, target);
    var entrance =
        requireLiveEntrance
            ? requireEntrance(actor.tenantId(), target, rule.targetResourceCode())
            : null;
    var schema =
        new RecordSchemaReference(
            target.activeVersion(), schemas.encode(target.activeSchema()).checksum());
    return new Conversion(
        binding,
        collectionBinding(rule, edge),
        new RecordPublicationIntent.Binding(schema, schema, target.target().metadataTarget(), 0),
        entrance);
  }

  private Map<String, RecordPublicationIntent.Binding> validateCollections(
      RuntimeActor actor,
      RecordWritePolicyPublicationCommand command,
      RuntimeRecordModel source,
      Conversion destination) {
    var bindings = new LinkedHashMap<String, RecordPublicationIntent.Binding>();
    var targets = new LinkedHashMap<String, RuntimeRecordModel>();
    targets.put(source.physicalModelId(), source);
    for (var rule : command.calculation().collectionsOrEmpty()) {
      if (destination != null && rule.relationId().equals(destination.binding().relationId())) {
        bindings.put(rule.relationId(), destination.edge());
        continue;
      }
      var edge = models.resolve(actor, rule.edgeModelId(), rule.edgeSchema());
      var target =
          targets.computeIfAbsent(rule.targetModelId(), id -> models.resolveCurrent(actor, id));
      RecordCollectionPublication.validate(rule, source, edge, target);
      requireEntrance(actor.tenantId(), target, rule.targetResourceCode());
      bindings.put(rule.relationId(), collectionBinding(rule, edge));
    }
    return Map.copyOf(bindings);
  }

  private RecordPublicationIntent.Binding collectionBinding(
      org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule rule,
      RuntimeRecordModel edge) {
    return new RecordPublicationIntent.Binding(
        new RecordSchemaReference(
            edge.activeVersion(), schemas.encode(edge.activeSchema()).checksum()),
        rule.edgeSchema(),
        edge.target().metadataTarget(),
        0);
  }

  private Map<String, RecordPublicationIntent.Resource> resources(
      final String tenant, final String model) {
    return resources(tenant, model, null);
  }

  private Map<String, RecordPublicationIntent.Resource> resources(
      final String tenant, final String model, final String resource) {
    // Scalar projection avoids stale persistence-context entities. Every association mutation takes
    // this model's write lock; no resource lock is acquired after it, including missing
    // projections.
    var query =
        em.createQuery(
                "select b.id, b.resourceCode, p.id, p.ownerKey, p.generation, p.formallyOffline,"
                    + " p.physicalModelId\n"
                    + "from PhysicalModelResourceBindingEntity b\n"
                    + "left join PhysicalResourceProjectionEntity p on p.tenantId = b.tenantId and"
                    + " p.resourceCode = b.resourceCode\n"
                    + "where b.tenantId = :tenant and b.physicalModelId = :model and b.deletedAt is"
                    + " null\n"
                    + (resource == null ? "" : " and b.resourceCode = :resource")
                    + " order by b.resourceCode, b.id",
                Object[].class)
            .setParameter("tenant", tenant)
            .setParameter("model", model)
            .setMaxResults(1025);
    if (resource != null) {
      query.setParameter("resource", resource);
    }
    var rows = query.getResultList();
    if (rows.size() > 1024) {
      throw invalid("Publication resource snapshot exceeds its bounded storage budget");
    }
    var result = new LinkedHashMap<String, RecordPublicationIntent.Resource>();
    for (var row : rows) {
      if (Boolean.TRUE.equals(row[5]) || row[3] != null && !model.equals(row[6])) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED,
            "An active publication resource association is offline or superseded");
      }
      var projection =
          row[2] == null
              ? null
              : new RecordPublicationIntent.Projection(
                  (String) row[2], (String) row[3], ((Number) row[4]).longValue(), (String) row[6]);
      if (result.put(
              (String) row[1], new RecordPublicationIntent.Resource((String) row[0], projection))
          != null) {
        throw invalid("Publication resource association is not unique");
      }
    }
    return Map.copyOf(result);
  }

  private void validateReferences(
      final RuntimeActor actor,
      final RecordWritePolicyPublicationCommand command,
      final Map<String, RecordPublicationPatchCompiler.Resolved> sources) {
    var targets = new LinkedHashMap<String, RuntimeRecordModel>();
    for (var rule : command.calculation().referencesOrEmpty()) {
      String sourceId =
          rule.source().scope().equals("parent")
              ? command.physicalModelId()
              : command.calculation().details().containsKey(rule.source().scope())
                  ? command.calculation().details().get(rule.source().scope()).physicalModelId()
                  : null;
      var source = sources.get(sourceId);
      if (source == null) {
        throw invalid("Reference publication does not match the owned source schema");
      }
      var reference =
          source.model().activeSchema().references().stream()
              .filter(
                  candidate ->
                      candidate.relationId().equals(rule.relationId())
                          && candidate.fieldId().equals(rule.source().fieldId())
                          && candidate.targetModelId().equals(rule.targetModelId()))
              .findFirst()
              .orElseThrow(
                  () -> invalid("Reference publication does not match the owned source schema"));
      var target =
          targets.computeIfAbsent(rule.targetModelId(), id -> models.resolveCurrent(actor, id));
      RecordReferenceRules.requireTargetIdentity(rule, source.model(), target);
      RecordReferenceRules.validateTargetDependencies(target, reference, rule.eligibility());
      RecordReferenceRules.snapshotLabel(target, reference, rule);
      if (!rule.retired()) {
        requireEntrance(actor.tenantId(), target, rule.targetResourceCode());
        RecordReferenceRules.validateTargetCondition(target, rule.eligibility());
      }
    }
  }

  private RecordPublicationIntent.Resource requireEntrance(
      String tenant, RuntimeRecordModel target, String resource) {
    var entrance = resources(tenant, target.physicalModelId(), resource).get(resource);
    if (entrance == null
        || entrance.projection() == null
        || entrance.projection().ownerKey() == null
        || entrance.projection().generation() < 1
        || !target.physicalModelId().equals(entrance.projection().physicalModelId())) {
      throw invalid("Reference publication requires its selected live managed target entrance");
    }
    return entrance;
  }

  private static DnaRuntimeException invalid(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE, message);
  }

  record Snapshot(
      Map<String, RecordPublicationPatchCompiler.Resolved> models,
      Map<String, RecordPublicationIntent.Binding> bindings,
      Map<String, Map<String, RecordPublicationIntent.Resource>> resources,
      Map<RecordSchemaReference, RuntimeRecordModel> historical,
      Map<String, RecordPublicationIntent.Binding> collectionBindings,
      Map<String, RuntimeRecordModel> retiringCollections,
      Map<String, RecordPublicationIntent.Binding> retirementBindings,
      Conversion conversion, Rebind rebind) {
    Snapshot(
        Map<String, RecordPublicationPatchCompiler.Resolved> models,
        Map<String, RecordPublicationIntent.Binding> bindings,
        Map<String, Map<String, RecordPublicationIntent.Resource>> resources,
        Map<RecordSchemaReference, RuntimeRecordModel> historical,
        Map<String, RecordPublicationIntent.Binding> collectionBindings,
        Map<String, RuntimeRecordModel> retiringCollections,
        Map<String, RecordPublicationIntent.Binding> retirementBindings, Conversion conversion) {
      this(models, bindings, resources, historical, collectionBindings, retiringCollections,
          retirementBindings, conversion, null);
    }
    Snapshot(
        Map<String, RecordPublicationPatchCompiler.Resolved> models,
        Map<String, RecordPublicationIntent.Binding> bindings,
        Map<String, Map<String, RecordPublicationIntent.Resource>> resources,
        Map<RecordSchemaReference, RuntimeRecordModel> historical,
        Map<String, RecordPublicationIntent.Binding> collectionBindings,
        Map<String, RuntimeRecordModel> retiringCollections,
        Map<String, RecordPublicationIntent.Binding> retirementBindings) {
      this(
          models,
          bindings,
          resources,
          historical,
          collectionBindings,
          retiringCollections,
          retirementBindings,
          null);
    }

    Snapshot(
        Map<String, RecordPublicationPatchCompiler.Resolved> models,
        Map<String, RecordPublicationIntent.Binding> bindings,
        Map<String, Map<String, RecordPublicationIntent.Resource>> resources,
        Map<RecordSchemaReference, RuntimeRecordModel> historical,
        Map<String, RecordPublicationIntent.Binding> collectionBindings) {
      this(models, bindings, resources, historical, collectionBindings, Map.of(), Map.of());
    }
  }

  record Conversion(
      RecordCollectionBinding binding,
      RecordPublicationIntent.Binding edge,
      RecordPublicationIntent.Binding target,
      RecordPublicationIntent.Resource entrance) {}

  record Rebind(Conversion source, Conversion destination) {}
}
