package org.simplepoint.plugin.dna.runtime.service.record;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

/**
 * Qualifies changed references on the borrowed Calcite transaction after deterministic row locks.
 */
final class RecordReferenceAdmissionSession {
  private final RecordReferenceAdmission.Plan plan;
  private final Connection connection;
  private final ManagedRuntimeDataSource handle;
  private final String tenant;
  private final RecordExecutionDeadline deadline;
  private final RecordSqlCompiler compiler;
  private final Function<RecordSelectPlan, CalciteQueryResult> reader;
  private RecordWritePreparation validated;

  RecordReferenceAdmissionSession(
      RecordReferenceAdmission.Plan plan,
      Connection connection,
      ManagedRuntimeDataSource handle,
      String tenant,
      RecordExecutionDeadline deadline,
      RecordSqlCompiler compiler,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    this.plan = plan;
    this.connection = connection;
    this.handle = handle;
    this.tenant = tenant;
    this.deadline = deadline;
    this.compiler = compiler;
    this.reader = reader;
  }

  void validate(RecordWritePreparation prepared) throws SQLException {
    validated = null;
    if (connection.getAutoCommit()
        || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
      throw RecordReferenceAdmission.incompatible();
    }
    Map<String, RecordReferenceCheck> checks = new HashMap<>();
    var locks = new RecordRowLocks(connection, handle, tenant, deadline, plan.sources());
    Map<String, TreeSet<String>> qualifying = new TreeMap<>();
    Map<String, Map<String, Long>> observedVersions = new HashMap<>();
    for (var check : prepared.references()) {
      var binding = plan.bindings().get(check.relationId());
      if (binding == null
          || !binding.sourceModelId().equals(check.sourceModelId())
          || checks.putIfAbsent(check.relationId() + ":" + check.sourceRecordId(), check) != null) {
        throw RecordReferenceAdmission.incompatible();
      }
      if (check.mappingRevision() != null) {
        String revision =
            check.targetRecordId() == null
                ? RecordReferenceMappingClear.bind(plan, check.relationId(), tenant).revision()
                : plan.mapping(check.relationId()).revision();
        if (!revision.equals(check.mappingRevision())) {
          throw conflict();
        }
      }
      if (check.targetRecordId() == null) {
        if (check.requireAuthorization()) {
          throw RecordReferenceAdmission.incompatible();
        }
        continue;
      }
      var source = plan.sources().get(check.sourceModelId());
      var target =
          new PhysicalSchemaTarget(
              binding.rule().targetModelId(),
              source.target().referenceTargets().get(binding.rule().targetModelId()));
      locks.reference(target, check.targetRecordId());
      if (check.expectedTargetVersion() != null) {
        var expected =
            observedVersions.computeIfAbsent(check.relationId(), ignored -> new HashMap<>());
        var previous = expected.putIfAbsent(check.targetRecordId(), check.expectedTargetVersion());
        if (previous != null && !previous.equals(check.expectedTargetVersion())) {
          throw conflict();
        }
      }
      if (check.requireAuthorization()) {
        if (binding.target() == null) {
          throw conflict();
        }
        qualifying
            .computeIfAbsent(check.relationId(), ignored -> new TreeSet<>())
            .add(check.targetRecordId());
      }
    }
    for (var statement : prepared.statements()) {
      if (statement.subject() == null) {
        continue;
      }
      var source = plan.sources().get(statement.subject().modelId());
      if (source == null || !tenant.equals(statement.subject().tenantId())) {
        throw RecordReferenceAdmission.incompatible();
      }
      locks.mutation(statement);
      if (statement.mutationType() != RecordMutationType.DELETE) {
        for (var reference : source.activeSchema().references()) {
          if (!checks.containsKey(reference.relationId() + ":" + statement.recordId())) {
            throw RecordReferenceAdmission.incompatible();
          }
        }
      }
    }
    var required = prepared.requiredReference();
    RecordReferenceAdmission.Target requiredTarget = null;
    if (required != null) {
      var binding = plan.bindings().get(required.relationId());
      if (binding == null || !binding.sourceModelId().equals(required.sourceModelId())) {
        throw RecordReferenceAdmission.incompatible();
      }
      requiredTarget = binding.target();
      if (requiredTarget == null) {
        throw conflict();
      }
      locks.reference(requiredTarget.model().target(), required.targetRecordId());
    }
    // Source write locks and target read locks share one order, avoiding the A→B / B→A
    // upgrade deadlock of taking all target SHARE locks before source UPDATE locks.
    locks.acquire();
    if (requiredTarget != null) {
      try {
        if (!RecordReferenceVisibility.select(
                requiredTarget.model(),
                requiredTarget.access(),
                java.util.List.of(required.targetRecordId()),
                compiler,
                reader)
            .contains(required.targetRecordId())) {
          throw conflict();
        }
      } catch (DnaRuntimeException failure) {
        if (failure.errorCode() == DnaRuntimeErrorCode.RECORD_QUERY_INVALID
            || failure.errorCode() == DnaRuntimeErrorCode.RECORD_ACCESS_DENIED) {
          throw conflict();
        }
        throw failure;
      }
    }
    for (var entry : qualifying.entrySet()) {
      var binding = plan.bindings().get(entry.getKey());
      var target = binding.target();
      var condition =
          RecordReferenceRules.validateTargetCondition(
              target.model(), binding.rule().eligibility());
      var ids = new ArrayList<>(entry.getValue());
      for (int offset = 0; offset < ids.size(); offset += 100) {
        var selected = ids.subList(offset, Math.min(ids.size(), offset + 100));
        var expected = observedVersions.getOrDefault(entry.getKey(), Map.of());
        boolean includeVersion = selected.stream().anyMatch(expected::containsKey);
        final RecordSelectPlan query;
        try {
          query =
              compiler.referenceTargets(
                  target.model(), target.access(), selected, condition, includeVersion);
        } catch (DnaRuntimeException failure) {
          if (failure.errorCode() == DnaRuntimeErrorCode.RECORD_COMMAND_INVALID
              || failure.errorCode() == DnaRuntimeErrorCode.RECORD_QUERY_INVALID
              || failure.errorCode() == DnaRuntimeErrorCode.RECORD_ACCESS_DENIED) {
            throw conflict();
          }
          throw failure;
        }
        var result = reader.apply(query);
        if (result.truncated()) {
          throw RecordReferenceAdmission.incompatible();
        }
        var found = new HashSet<String>();
        for (var row : result.rows()) {
          if (row.size() != (includeVersion ? 2 : 1)
              || !(row.getFirst() instanceof String id)
              || !found.add(id)) {
            throw RecordReferenceAdmission.incompatible();
          }
          if (expected.containsKey(id)) {
            final long actual;
            try {
              actual = new java.math.BigDecimal(row.get(1).toString()).longValueExact();
            } catch (RuntimeException corrupt) {
              throw RecordReferenceAdmission.incompatible();
            }
            if (actual != expected.get(id)) {
              throw conflict();
            }
          }
        }
        if (!found.equals(new HashSet<>(selected))) {
          throw conflict();
        }
      }
    }
    validated = prepared;
  }

  org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView capture(
      RecordWritePreparation prepared,
      org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView snapshot) {
    if (validated != prepared) {
      throw RecordReferenceAdmission.incompatible();
    }
    return RecordReferenceSnapshots.capture(snapshot, prepared, plan, compiler, reader);
  }

  RecordReferenceSnapshots.Captured captureWithOrigins(
      RecordWritePreparation prepared,
      org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView snapshot) {
    if (validated != prepared) {
      throw RecordReferenceAdmission.incompatible();
    }
    return RecordReferenceSnapshots.captureWithOrigins(snapshot, prepared, plan, compiler, reader);
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "The selected reference is unavailable or does not meet its current qualification policy");
  }
}
