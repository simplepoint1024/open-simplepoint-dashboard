package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/** Transaction-local derivation of provenance from the same prospective calculation inputs. */
final class RecordDerivedCopyWrite {
  private RecordDerivedCopyWrite() {}

  static List<RecordReferenceCopyMutation> prepare(
      RecordCalculationWriteSupport.Binding binding,
      String tenantId,
      Map<String, List<RecordView>> before,
      OwnedRecordView after,
      List<RecordSqlStatement> statements,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    return capture(
            binding,
            binding.parent(),
            binding.installed(),
            tenantId,
            before,
            after,
            statements,
            reader)
        .mutations();
  }

  /** Preview shares the same prospective proof but never executes its mutation plans. */
  static Prepared capture(
      RecordCalculationWriteSupport.Binding binding,
      RuntimeRecordModel parentModel,
      RecordCalculationStore.Installed installed,
      String tenantId,
      Map<String, List<RecordView>> before,
      OwnedRecordView after,
      List<RecordSqlStatement> statements,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    return capture(
        binding, parentModel, installed, tenantId, before, after, statements, reader, Map.of());
  }

  /** Confirmed copies enter the same prospective input proof before formula dependency capture. */
  static Prepared capture(
      RecordCalculationWriteSupport.Binding binding,
      RuntimeRecordModel parentModel,
      RecordCalculationStore.Installed installed,
      String tenantId,
      Map<String, List<RecordView>> before,
      OwnedRecordView after,
      List<RecordSqlStatement> statements,
      Function<RecordSelectPlan, CalciteQueryResult> reader,
      Map<List<String>, RecordReferenceCopyEffects.Applied> copiedInputs) {
    if (installed == null || installed.policy().queryProtection().isEmpty()) {
      if (!copiedInputs.isEmpty()) {
        throw RecordReferenceAdmission.incompatible();
      }
      return new Prepared(List.of(), List.of());
    }
    if (binding == null && !copiedInputs.isEmpty()) {
      throw RecordReferenceAdmission.incompatible();
    }
    if (binding != null
        && (!binding.parent().physicalModelId().equals(parentModel.physicalModelId())
            || !binding.parent().target().equals(parentModel.target())
            || !binding.parent().activeSchema().equals(parentModel.activeSchema())
            || !java.util.Objects.equals(binding.installed(), installed))) {
      throw RecordReferenceAdmission.incompatible();
    }
    String parent = parentModel.physicalModelId();
    Map<String, List<RecordView>> rows = new LinkedHashMap<>(after.details());
    rows.put(parent, List.of(after.parent()));
    var state = RecordReferenceCopyState.capture(parent, tenantId, before, rows, reader);
    final var previous = state.previous();
    var inputs =
        copiedInputs.isEmpty()
            ? state.prospective()
            : RecordReferenceCopiedInputs.merge(binding, after, state.prospective(), copiedInputs);
    Map<List<String>, RecordReferenceCopySnapshot> outputs = new LinkedHashMap<>();
    var captured =
        binding == null
            ? List.copyOf(inputs)
            : RecordDerivedCopyRequirements.capture(binding, after, inputs);
    RecordReferenceCopyBudget.validate(parent, captured, installed.policy().queryProtection());
    captured.forEach(
        snapshot ->
            outputs.put(
                List.of(
                    snapshot.detailModelId() == null ? parent : snapshot.detailModelId(),
                    snapshot.sourceRecordId()),
                snapshot));
    Map<List<String>, RecordSqlStatement> writes = new LinkedHashMap<>();
    for (var statement : statements) {
      if (statement.mutationType() == RecordMutationType.DELETE) {
        continue;
      }
      if (statement.subject() == null
          || !tenantId.equals(statement.subject().tenantId())
          || writes.putIfAbsent(
                  List.of(statement.subject().modelId(), statement.recordId()), statement)
              != null) {
        throw RecordReferenceAdmission.incompatible();
      }
    }
    List<RecordReferenceCopyMutation> result = new ArrayList<>();
    rows.forEach(
        (model, values) ->
            values.forEach(
                row -> {
                  var identity = List.of(model, row.id());
                  var old = previous.get(identity);
                  var output = outputs.get(identity);
                  var copies =
                      output == null
                          ? List
                              .<org.simplepoint.plugin.dna.runtime.api.record
                                      .RecordReferenceCopyOrigin>
                                  of()
                          : output.origins();
                  var derived =
                      output == null ? List.<RecordDerivedCopyOrigin>of() : output.derivedOrigins();
                  var statement = writes.get(identity);
                  if (statement == null) {
                    // Recomputed output must never acquire a new proof without a corresponding
                    // business CAS.
                    if (!copies.equals(old == null ? List.of() : old.origins())
                        || !derived.equals(old == null ? List.of() : old.derivedOrigins())
                        || old != null && row.version() != old.sourceVersion()) {
                      throw RecordReferenceAdmission.incompatible();
                    }
                  } else if (old != null || output != null) {
                    if (statement.resultVersion() != row.version()) {
                      throw RecordReferenceAdmission.incompatible();
                    }
                    var copy =
                        new RecordReferenceCopyMutation(
                            new RecordReferenceCopyStore.Key(tenantId, model, row.id()),
                            old,
                            row.version(),
                            copies,
                            derived);
                    copy.verify(statements);
                    result.add(copy);
                  }
                }));
    return new Prepared(result, captured);
  }

  record Prepared(
      List<RecordReferenceCopyMutation> mutations, List<RecordReferenceCopySnapshot> origins) {
    Prepared {
      mutations = List.copyOf(mutations);
      origins = List.copyOf(origins);
    }
  }
}
