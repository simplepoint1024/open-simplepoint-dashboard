package org.simplepoint.plugin.dna.runtime.service.relation;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalRelationDefinitionEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalRelationGuardEntity;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalReferenceResolver;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.ReferenceFieldDependencies;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.springframework.stereotype.Component;

/**
 * DNA-owned storage dependencies, independent of resource authorization and UI metadata. The
 * caller's control transaction holds the catalog lock through target transaction completion. Schema
 * writers take the exclusive lock before model/resource locks; failed DDL retains guards.
 */
@Component
public class PhysicalRelationCatalog {
  private final EntityManager em;
  private final PhysicalRelationCatalogBoundary boundary;
  private final PhysicalModelVersionRepository versions;
  private final PhysicalModelBindingRepository bindings;
  private final PhysicalSchemaCodec schemas;
  private final PhysicalReferenceResolver references;

  /**
   * Creates the owned relation catalog with its durable transaction boundary and model resolver.
   */
  public PhysicalRelationCatalog(
      EntityManager em,
      PhysicalRelationCatalogBoundary boundary,
      PhysicalModelVersionRepository versions,
      PhysicalModelBindingRepository bindings,
      PhysicalSchemaCodec schemas,
      PhysicalReferenceResolver references) {
    this.em = em;
    this.boundary = boundary;
    this.versions = versions;
    this.bindings = bindings;
    this.schemas = schemas;
    this.references = references;
  }

  /** Ensures the tenant has a durable relation barrier. */
  public void initialize(String tenant) {
    boundary.initialize(tenant);
  }

  /** Acquires the tenant catalog mutation boundary in the current transaction. */
  public void write(String tenant) {
    boundary.write(tenant);
  }

  /** Requires the catalog write lock before changing a draft; retry may retain its own guards. */
  public void requireMutable(String tenant, String modelId, String ownMigration) {
    boundary.requireHeld(tenant, true);
    var model = model(tenant, modelId);
    var latest = schema(tenant, modelId, model.getLatestVersion());
    var participants = new TreeSet<String>();
    participants.add(modelId);
    if (latest.owner() != null) {
      participants.add(latest.owner().parentModelId());
    }
    requireUnguarded(tenant, participants, ownMigration);
  }

  /**
   * Historical and offline sources retain their dependencies until an explicit retirement protocol
   * releases them. This is a storage safety check, never a permission-filtered discovery query.
   */
  public void requireCompatibleTarget(
      String tenant, String modelId, PhysicalSchemaDefinition desired) {
    boundary.requireHeld(tenant, true);
    model(tenant, modelId);
    var incoming =
        em.createQuery(
                "from PhysicalRelationDefinitionEntity r where r.tenantId=:tenant and"
                    + " r.targetModelId=:model",
                PhysicalRelationDefinitionEntity.class)
            .setParameter("tenant", tenant)
            .setParameter("model", modelId)
            .setMaxResults(4097)
            .getResultList();
    if (incoming.size() > 4096) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_INVALID,
          "The retained relation dependency inspection limit was exceeded");
    }
    var snapshots = new java.util.HashMap<SourceVersion, PhysicalSchemaDefinition>();
    for (var row : incoming) {
      var key = new SourceVersion(row.getSourceModelId(), row.getSourceVersion());
      var source =
          snapshots.computeIfAbsent(key, value -> schema(tenant, value.model(), value.version()));
      var reference =
          source.references().stream()
              .filter(value -> value.relationId().equals(row.getRelationId()))
              .findFirst()
              .orElseThrow(PhysicalRelationCatalog::drift);
      if (!reference.targetModelId().equals(modelId)
          || !reference.fieldId().equals(row.getFieldId())
          || reference.unique() != row.isUniqueTarget()) {
        throw drift();
      }
      ReferenceFieldDependencies.requireCompatible(reference, desired);
    }
  }

  /** Stages an immutable dependency snapshot and all durable guards before target DDL starts. */
  public void prepare(
      String tenant,
      String actor,
      SchemaMigrationEntity migration,
      PhysicalSchemaDefinition schema,
      PhysicalSchemaTarget target) {
    boundary.requireHeld(tenant, true);
    requireMigration(tenant, migration, target.physicalModelId());
    if (migration.getStatus() != SchemaMigrationStatus.VALIDATING) {
      throw drift();
    }
    requireSnapshot(tenant, migration, schema, target);
    requireCompatibleTarget(tenant, target.physicalModelId(), schema);
    var expected = snapshot(schema, target);
    var participants = participants(target.physicalModelId(), schema, expected);
    if (migration.getFromVersion() != null) {
      definitions(tenant, target.physicalModelId(), migration.getFromVersion())
          .forEach(
              row -> {
                participants.add(row.getTargetModelId());
                participants.add(row.getSourceRootModelId());
              });
    }
    requireUnguarded(tenant, participants, migration.getId());
    // Target status can change between initial planning and READY. Re-resolve under the barrier.
    if (!target.equals(references.resolve(tenant, target, schema))) {
      throw drift();
    }
    var existingGuards = guards(tenant, migration.getId());
    var existing = definitions(tenant, target.physicalModelId(), migration.getTargetVersion());
    requireRoots(existing, root(target.physicalModelId(), schema));
    if (!existingGuards.isEmpty()) {
      if (existingGuards.stream().anyMatch(PhysicalRelationGuardEntity::isReleased)
          || !guardModels(existingGuards).equals(participants)
          || !values(existing).equals(expected)) {
        throw drift();
      }
      return;
    }
    if (!existing.isEmpty()) {
      throw drift();
    }
    for (var value : expected) {
      var row = new PhysicalRelationDefinitionEntity();
      row.setId(UUID.randomUUID().toString());
      row.setTenantId(tenant);
      row.setCreatedBy(actor);
      row.setUpdatedBy(actor);
      row.setSourceModelId(target.physicalModelId());
      row.setSourceRootModelId(root(target.physicalModelId(), schema));
      row.setSourceVersion(migration.getTargetVersion());
      row.setRelationId(value.relation());
      row.setFieldId(value.field());
      row.setColumnName(value.column());
      row.setTargetModelId(value.target());
      row.setUniqueTarget(value.unique());
      row.setDataSourceId(value.dataSource());
      row.setCatalogName(value.catalog());
      row.setSchemaName(value.namespace());
      row.setSourceTable(value.sourceTable());
      row.setTargetTable(value.targetTable());
      em.persist(row);
    }
    for (var participant : participants) {
      model(tenant, participant);
      var guard = new PhysicalRelationGuardEntity();
      guard.setId(UUID.randomUUID().toString());
      guard.setTenantId(tenant);
      guard.setCreatedBy(actor);
      guard.setUpdatedBy(actor);
      guard.setMigrationId(migration.getId());
      guard.setModelId(participant);
      em.persist(guard);
    }
    em.flush();
  }

  /**
   * Must commit atomically with verified migration activation. There is intentionally no failure
   * release.
   */
  public void activate(
      String tenant,
      String actor,
      SchemaMigrationEntity migration,
      PhysicalSchemaDefinition schema,
      PhysicalSchemaTarget target) {
    boundary.requireHeld(tenant, true);
    requireMigration(tenant, migration, target.physicalModelId());
    if (migration.getStatus() != SchemaMigrationStatus.VERIFYING) {
      throw drift();
    }
    requireSnapshot(tenant, migration, schema, target);
    var expected = snapshot(schema, target);
    var participants = participants(target.physicalModelId(), schema, expected);
    if (migration.getFromVersion() != null) {
      definitions(tenant, target.physicalModelId(), migration.getFromVersion())
          .forEach(
              row -> {
                participants.add(row.getTargetModelId());
                participants.add(row.getSourceRootModelId());
              });
    }
    var held = guards(tenant, migration.getId());
    requireRoots(
        definitions(tenant, target.physicalModelId(), migration.getTargetVersion()),
        root(target.physicalModelId(), schema));
    if (held.isEmpty()
        || held.stream().anyMatch(PhysicalRelationGuardEntity::isReleased)
        || !guardModels(held).equals(participants)
        || !values(definitions(tenant, target.physicalModelId(), migration.getTargetVersion()))
            .equals(expected)) {
      throw drift();
    }
    for (var guard : held) {
      guard.setReleased(true);
      guard.setUpdatedBy(actor);
    }
  }

  /**
   * Verifies detached execution metadata while keeping the shared catalog lock until target commit.
   */
  public void admit(String tenant, Collection<Scope> scopes) {
    boundary.read(tenant);
    if (scopes == null || scopes.isEmpty() || scopes.size() > 256) {
      throw drift();
    }
    var affected = new TreeSet<String>();
    for (var scope : scopes) {
      var current = model(tenant, scope.target().physicalModelId());
      if (current.getStatus() != PhysicalModelStatus.ACTIVE
          || !Objects.equals(current.getActiveVersion(), scope.version())) {
        throw unavailable();
      }
      if (!schema(tenant, current.getId(), scope.version()).equals(scope.schema())) {
        throw drift();
      }
      if (!scope
          .target()
          .equals(
              references.resolveStored(
                  tenant, ownTarget(tenant, current.getId()), scope.schema()))) {
        throw drift();
      }
      var actual = definitions(tenant, current.getId(), scope.version());
      requireRoots(actual, root(current.getId(), scope.schema()));
      if (!values(actual).equals(snapshot(scope.schema(), scope.target()))) {
        throw drift();
      }
      affected.add(current.getId());
      affected.add(root(current.getId(), scope.schema()));
      actual.forEach(row -> affected.add(row.getTargetModelId()));
    }
    requireUnguarded(tenant, affected, null);
  }

  /**
   * Calculation workers resolve execution metadata after this entry, under the same read barrier.
   */
  public void admitModels(String tenant, Collection<String> modelIds) {
    boundary.read(tenant);
    if (modelIds == null || modelIds.isEmpty() || modelIds.size() > 256) {
      throw drift();
    }
    var scopes = new java.util.ArrayList<Scope>();
    for (String id : modelIds) {
      var current = model(tenant, id);
      if (current.getStatus() != PhysicalModelStatus.ACTIVE || current.getActiveVersion() == null) {
        throw unavailable();
      }
      var schema = schema(tenant, id, current.getActiveVersion());
      scopes.add(
          new Scope(
              current.getActiveVersion(),
              schema,
              references.resolveStored(tenant, ownTarget(tenant, id), schema)));
    }
    admit(tenant, scopes);
  }

  private PhysicalSchemaTarget ownTarget(String tenant, String model) {
    var binding =
        bindings.findActiveByModel(tenant, model).orElseThrow(PhysicalRelationCatalog::drift);
    return new PhysicalSchemaTarget(
            model,
            new RuntimeMetadataTarget(
                binding.getDataSourceId(),
                binding.getCatalogName(),
                binding.getSchemaName(),
                binding.getTableName()))
        .withOwnership(binding.getPhysicalNamespaceId(), tenant);
  }

  private void requireSnapshot(
      String tenant,
      SchemaMigrationEntity migration,
      PhysicalSchemaDefinition schema,
      PhysicalSchemaTarget target) {
    var own = ownTarget(tenant, target.physicalModelId());
    if (!schema(tenant, target.physicalModelId(), migration.getTargetVersion()).equals(schema)
        || !own.metadataTarget().equals(target.metadataTarget())
        || !java.util.Objects.equals(own.ownership(), target.ownership())) {
      throw drift();
    }
  }

  private PhysicalModelEntity model(String tenant, String id) {
    var row = em.find(PhysicalModelEntity.class, id);
    if (row == null || !tenant.equals(row.getTenantId()) || row.getDeletedAt() != null) {
      throw unavailable();
    }
    return row;
  }

  private PhysicalSchemaDefinition schema(String tenant, String model, int version) {
    return schemas.decode(
        versions
            .findActiveVersion(tenant, model, version)
            .orElseThrow(PhysicalRelationCatalog::drift)
            .getSchemaJson());
  }

  private List<PhysicalRelationDefinitionEntity> definitions(
      String tenant, String model, int version) {
    var result =
        em.createQuery(
                "from PhysicalRelationDefinitionEntity r where r.tenantId=:tenant "
                    + "and r.sourceModelId=:model and r.sourceVersion=:version",
                PhysicalRelationDefinitionEntity.class)
            .setParameter("tenant", tenant)
            .setParameter("model", model)
            .setParameter("version", version)
            .setMaxResults(51)
            .getResultList();
    if (result.size() > 50) {
      throw drift();
    }
    return result;
  }

  private List<PhysicalRelationGuardEntity> guards(String tenant, String migration) {
    var result =
        em.createQuery(
                "from PhysicalRelationGuardEntity g where g.tenantId=:tenant and"
                    + " g.migrationId=:migration",
                PhysicalRelationGuardEntity.class)
            .setParameter("tenant", tenant)
            .setParameter("migration", migration)
            .setMaxResults(105)
            .getResultList();
    if (result.size() > 104) {
      throw drift();
    }
    return result;
  }

  private void requireUnguarded(String tenant, Set<String> models, String ownMigration) {
    String excludingOwn = ownMigration == null ? "" : " and g.migrationId<>:own";
    var query =
        em.createQuery(
                "select g.id from PhysicalRelationGuardEntity g where g.tenantId=:tenant "
                    + "and g.modelId in :models and g.released=false"
                    + excludingOwn,
                String.class)
            .setParameter("tenant", tenant)
            .setParameter("models", models)
            .setMaxResults(1);
    if (ownMigration != null) {
      query.setParameter("own", ownMigration);
    }
    if (!query.getResultList().isEmpty()) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT,
          "A dependent schema publication has not completed verified activation");
    }
  }

  private static Set<String> guardModels(List<PhysicalRelationGuardEntity> guards) {
    var result = new TreeSet<String>();
    guards.forEach(g -> result.add(g.getModelId()));
    return result;
  }

  private static TreeSet<String> participants(
      String id, PhysicalSchemaDefinition schema, Set<StorageReference> refs) {
    var result = new TreeSet<String>();
    result.add(id);
    result.add(root(id, schema));
    refs.forEach(ref -> result.add(ref.target()));
    return result;
  }

  private static String root(String id, PhysicalSchemaDefinition schema) {
    return schema.owner() == null ? id : schema.owner().parentModelId();
  }

  private static Set<StorageReference> snapshot(
      PhysicalSchemaDefinition schema, PhysicalSchemaTarget target) {
    var own = target.metadataTarget();
    var result = new java.util.HashSet<StorageReference>();
    for (var ref : schema.references()) {
      var field =
          schema.fields().stream()
              .filter(f -> f.fieldId().equals(ref.fieldId()))
              .findFirst()
              .orElseThrow(PhysicalRelationCatalog::drift);
      var dest = target.referenceTargets().get(ref.targetModelId());
      if (dest == null
          || !own.dataSourceId().equals(dest.dataSourceId())
          || !own.catalogName().equals(dest.catalogName())
          || !own.schemaName().equals(dest.schemaName())) {
        throw drift();
      }
      result.add(
          new StorageReference(
              ref.relationId(),
              ref.fieldId(),
              field.code(),
              ref.targetModelId(),
              ref.unique(),
              own.dataSourceId(),
              own.catalogName(),
              own.schemaName(),
              own.tableName(),
              dest.tableName()));
    }
    if (result.size() != schema.references().size()) {
      throw drift();
    }
    return Set.copyOf(result);
  }

  private static void requireRoots(List<PhysicalRelationDefinitionEntity> rows, String root) {
    if (rows.stream().anyMatch(row -> !root.equals(row.getSourceRootModelId()))) {
      throw drift();
    }
  }

  private static Set<StorageReference> values(List<PhysicalRelationDefinitionEntity> rows) {
    var result = new java.util.HashSet<StorageReference>();
    rows.forEach(
        r ->
            result.add(
                new StorageReference(
                    r.getRelationId(),
                    r.getFieldId(),
                    r.getColumnName(),
                    r.getTargetModelId(),
                    r.isUniqueTarget(),
                    r.getDataSourceId(),
                    r.getCatalogName(),
                    r.getSchemaName(),
                    r.getSourceTable(),
                    r.getTargetTable())));
    if (result.size() != rows.size()) {
      throw drift();
    }
    return Set.copyOf(result);
  }

  private static void requireMigration(
      String tenant, SchemaMigrationEntity migration, String model) {
    if (!tenant.equals(migration.getTenantId())
        || !model.equals(migration.getPhysicalModelId())
        || migration.getPlanJson() == null
        || migration.getPlanChecksum() == null) {
      throw drift();
    }
  }

  private static DnaRuntimeException drift() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED,
        "Relation directory does not match immutable publication metadata");
  }

  private static DnaRuntimeException unavailable() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_ACTIVE,
        "Relation participant is not available for this operation");
  }

  /** Logical version and resolved physical target checked together by relation admission. */
  public record Scope(int version, PhysicalSchemaDefinition schema, PhysicalSchemaTarget target) {}

  private record StorageReference(
      String relation,
      String field,
      String column,
      String target,
      boolean unique,
      String dataSource,
      String catalog,
      String namespace,
      String sourceTable,
      String targetTable) {}

  private record SourceVersion(String model, int version) {}
}
