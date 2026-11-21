package org.simplepoint.plugin.dna.runtime.service.record;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

/** Same-transaction ordered mutation/reference locks, including models with only incoming links. */
final class RecordRowLocks {
  private final Connection connection;
  private final ManagedRuntimeDataSource handle;
  private final String tenant;
  private final RecordExecutionDeadline deadline;
  private final Map<String, RuntimeRecordModel> sources;
  private final Map<String, Lock> locks = new TreeMap<>();
  private final java.util.Set<String> live = new java.util.HashSet<>();
  private boolean acquired;

  RecordRowLocks(
      Connection connection,
      ManagedRuntimeDataSource handle,
      String tenant,
      RecordExecutionDeadline deadline,
      Map<String, RuntimeRecordModel> sources) {
    this.connection = connection;
    this.handle = handle;
    this.tenant = tenant;
    this.deadline = deadline;
    this.sources = Map.copyOf(sources);
  }

  void mutation(RecordSqlStatement statement) {
    if (statement.subject() == null) {
      return;
    }
    var source = sources.get(statement.subject().modelId());
    if (source == null || !tenant.equals(statement.subject().tenantId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    add(source.target(), statement.recordId(), true, false);
  }

  void reference(PhysicalSchemaTarget target, String id) {
    add(target, id, false, true);
  }

  void reference(RecordReferenceCheck reference) {
    if (reference.targetRecordId() != null) {
      reference(
          target(reference.sourceModelId(), reference.relationId()), reference.targetRecordId());
    }
  }

  void discover(RecordSequenceLockPlan plan) {
    for (var source : plan.sources()) {
      add(source(source.modelId()).target(), source.recordId(), true, false);
    }
    for (var reference : plan.references()) {
      add(
          target(reference.sourceModelId(), reference.relationId()),
          reference.targetRecordId(),
          false,
          false);
    }
  }

  /** Never acquire an unplanned row late in a calculated sequence, even after an earlier effect. */
  void requireCovered(RecordWritePreparation prepared) {
    if (!acquired) {
      throw RecordReferenceAdmission.incompatible();
    }
    for (var statement : prepared.statements()) {
      if (statement.subject() == null) {
        continue;
      }
      if (!tenant.equals(statement.subject().tenantId())) {
        throw RecordReferenceAdmission.incompatible();
      }
      require(source(statement.subject().modelId()).target(), statement.recordId(), true, false);
    }
    for (var reference : prepared.references()) {
      require(source(reference.sourceModelId()).target(), reference.sourceRecordId(), true, false);
      if (reference.targetRecordId() != null) {
        require(
            target(reference.sourceModelId(), reference.relationId()),
            reference.targetRecordId(),
            false,
            true);
      }
    }
  }

  private void require(PhysicalSchemaTarget target, String id, boolean write, boolean required) {
    var lock = locks.get(key(target, id));
    if (lock == null
        || write && !lock.write()
        || !lock.target().metadataTarget().equals(target.metadataTarget())) {
      throw RecordReferenceAdmission.incompatible();
    }
    // An absent target cannot be locked by SELECT. It must not be newly acquired out of order
    // later.
    if (required && !live.contains(key(target, id))) {
      throw conflict();
    }
  }

  private RuntimeRecordModel source(String id) {
    var source = sources.get(id);
    if (source == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    return source;
  }

  private PhysicalSchemaTarget target(String sourceId, String relation) {
    var source = source(sourceId);
    var definition =
        source.activeSchema().references().stream()
            .filter(value -> value.relationId().equals(relation))
            .findFirst()
            .orElseThrow(RecordReferenceAdmission::incompatible);
    var target = source.target().referenceTargets().get(definition.targetModelId());
    if (target == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    return new PhysicalSchemaTarget(definition.targetModelId(), target);
  }

  void acquire() throws SQLException {
    if (acquired
        || connection.getAutoCommit()
        || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
      throw RecordReferenceAdmission.incompatible();
    }
    acquired = true;
    for (var lock : locks.values()) {
      var sql =
          handle
              .runtimeDialect()
              .recordReferenceSql(
                  handle.coreDialect(), handle.supportContext(), lock.target(), "id");
      try (var query =
          connection.prepareStatement(lock.write() ? sql.lockMutation() : sql.lockTarget())) {
        query.setQueryTimeout(Math.max(1, (deadline.remainingMillis() + 999) / 1000));
        query.setString(1, tenant);
        query.setString(2, lock.id());
        try (var rows = query.executeQuery()) {
          boolean present = rows.next();
          boolean available = present && rows.getObject(1) == null;
          if (available) {
            live.add(key(lock.target(), lock.id()));
          }
          if (lock.required() && !available) {
            throw conflict();
          }
          if (present && rows.next()) {
            throw RecordReferenceAdmission.incompatible();
          }
        }
      }
    }
  }

  private void add(PhysicalSchemaTarget target, String id, boolean write, boolean required) {
    if (acquired || target == null || id == null || id.isBlank()) {
      throw RecordReferenceAdmission.incompatible();
    }
    var metadata = target.metadataTarget();
    // The namespace is verified by the enclosing managed transaction, never selected by a value.
    var source =
        sources.values().stream()
            .findFirst()
            .orElseThrow(RecordReferenceAdmission::incompatible)
            .target()
            .metadataTarget();
    if (!source.dataSourceId().equals(metadata.dataSourceId())
        || !java.util.Objects.equals(source.catalogName(), metadata.catalogName())
        || !java.util.Objects.equals(source.schemaName(), metadata.schemaName())) {
      throw RecordReferenceAdmission.incompatible();
    }
    String key = key(target, id);
    locks.merge(
        key,
        new Lock(target, id, write, required),
        (first, second) -> {
          if (!first.target().metadataTarget().equals(second.target().metadataTarget())) {
            throw RecordReferenceAdmission.incompatible();
          }
          return new Lock(first.target(), id, first.write() || write, first.required() || required);
        });
    if (locks.size() > 10_451) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  private record Lock(PhysicalSchemaTarget target, String id, boolean write, boolean required) {}

  private static String key(PhysicalSchemaTarget target, String id) {
    return target.metadataTarget().tableName() + ":" + id;
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "The selected reference is unavailable or does not meet its current qualification policy");
  }
}
