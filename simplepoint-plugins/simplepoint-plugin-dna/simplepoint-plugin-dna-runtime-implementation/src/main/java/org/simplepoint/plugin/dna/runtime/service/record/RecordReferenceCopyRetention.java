package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;

/** Carries protected origins through ordinary edits after business rows have been locked. */
final class RecordReferenceCopyRetention {
  private RecordReferenceCopyRetention() {}

  static List<RecordReferenceCopyMutation> prepare(
      RecordWritePreparation prepared,
      RecordCalculationStore.Installed installed,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    if (installed == null || installed.policy().queryProtection().isEmpty()) {
      return prepared.copies();
    }
    var result = new ArrayList<>(prepared.copies());
    var explicit =
        prepared.copies().stream()
            .map(RecordReferenceCopyMutation::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Map<RecordReferenceCopyStore.Key, List<RecordSqlStatement>> changes = new LinkedHashMap<>();
    for (var statement : prepared.statements()) {
      var subject = statement.subject();
      if (subject == null || !installed.policy().queryProtection().containsKey(subject.modelId())) {
        continue;
      }
      var key =
          new RecordReferenceCopyStore.Key(
              subject.tenantId(), subject.modelId(), statement.recordId());
      if (!explicit.contains(key)) {
        changes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(statement);
      }
    }
    Map<List<String>, List<RecordReferenceCopyStore.Key>> models = new LinkedHashMap<>();
    changes.forEach(
        (key, statements) -> {
          if (statements.getLast().mutationType() != RecordMutationType.DELETE) {
            models
                .computeIfAbsent(
                    List.of(key.tenantId(), key.modelId()), ignored -> new ArrayList<>())
                .add(key);
          }
        });
    var store = new RecordReferenceCopyStore();
    for (var keys : models.values()) {
      for (int offset = 0; offset < keys.size(); offset += 100) {
        var batch = keys.subList(offset, Math.min(keys.size(), offset + 100));
        var stored = store.readBatch(batch, reader.apply(store.lookupBatch(batch)));
        for (var key : batch) {
          var previous = stored.get(key.recordId());
          if (previous == null) {
            continue;
          }
          var statements = changes.get(key);
          var first = statements.getFirst();
          if (first.mutationType() != RecordMutationType.UPDATE
              || previous.sourceVersion() != first.resultVersion() - 1) {
            throw RecordReferenceAdmission.incompatible();
          }
          // A transaction may update the same record more than once. Keep one final ledger effect
          // tied to its last business version; the business statements still perform every CAS.
          for (int index = 1; index < statements.size(); index++) {
            if (statements.get(index).mutationType() != RecordMutationType.UPDATE
                || statements.get(index).resultVersion()
                    != statements.get(index - 1).resultVersion() + 1) {
              throw RecordReferenceAdmission.incompatible();
            }
          }
          var copy =
              new RecordReferenceCopyMutation(
                  key,
                  previous,
                  statements.getLast().resultVersion(),
                  previous.origins(),
                  previous.derivedOrigins());
          copy.verify(prepared.statements());
          result.add(copy);
        }
      }
    }
    return List.copyOf(result);
  }
}
