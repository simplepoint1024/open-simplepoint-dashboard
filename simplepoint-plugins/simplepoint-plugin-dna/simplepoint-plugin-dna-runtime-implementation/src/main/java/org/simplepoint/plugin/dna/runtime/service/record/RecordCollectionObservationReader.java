package org.simplepoint.plugin.dna.runtime.service.record;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;

/**
 * Bounded qualified observations using the caller's transaction reader. Repeat after acquiring
 * every candidate row lock; resource grants, write policy and installed governance are fenced by
 * the owning coordinator, not by this query adapter.
 */
final class RecordCollectionObservationReader {
  private final RecordCollectionBinding binding;
  private final CollectionRecordCommand command;
  private final RecordAccessContext sourceAccess;
  private final RecordAccessContext targetAccess;
  private final RecordSqlCompiler compiler;
  private final RecordValueCondition mutableWhen;
  private final RecordValueCondition eligibility;

  RecordCollectionObservationReader(
      RecordCollectionBinding binding,
      CollectionRecordCommand command,
      RecordCollectionRule rule,
      RecordAccessContext sourceAccess,
      RecordAccessContext targetAccess,
      RecordSqlCompiler compiler) {
    RecordCollectionPublication.validate(rule, binding.source(), binding.edge(), binding.target());
    if (!sourceAccess.actor().equals(targetAccess.actor())
        || !rule.targetResourceCode().equals(targetAccess.resourceCode())
        || !command.sourceModelId().equals(binding.source().physicalModelId())
        || !command.relationId().equals(binding.relationId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    this.binding = binding;
    this.command = command;
    this.sourceAccess = sourceAccess;
    this.targetAccess = targetAccess;
    this.compiler = compiler;
    mutableWhen =
        RecordReferenceRules.validateTargetCondition(binding.source(), rule.mutableWhen());
    eligibility =
        RecordReferenceRules.validateTargetCondition(binding.target(), rule.eligibility());
  }

  RecordCollectionMutationPlan.Observations read(
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var source =
        versions(
            binding.source(), sourceAccess, List.of(command.sourceRecordId()), mutableWhen, reader);
    var ids = command.mutations().stream().map(CollectionEdgeMutation::targetRecordId).toList();
    var targets = versions(binding.target(), targetAccess, ids, null, reader);
    var linking =
        command.mutations().stream()
            .filter(value -> value.mode() == CollectionEdgeMutation.Mode.LINK)
            .map(CollectionEdgeMutation::targetRecordId)
            .toList();
    // Removal still requires current target read permission, but need not pass new-link
    // eligibility.
    if (eligibility != null && !linking.isEmpty()) {
      var eligible = versions(binding.target(), targetAccess, linking, eligibility, reader);
      if (eligible.entrySet().stream()
          .anyMatch(entry -> !entry.getValue().equals(targets.get(entry.getKey())))) {
        throw conflict();
      }
    }
    var keys =
        ids.stream()
            .map(
                id ->
                    RecordCollectionEdgeKey.of(
                        binding, sourceAccess.actor().tenantId(), command.sourceRecordId(), id))
            .toList();
    var stored =
        RecordCollectionEdgeReads.read(
            binding, keys, reader.apply(RecordCollectionSql.lookup(binding, keys)));
    var edges = new LinkedHashMap<String, RecordCollectionEdgeState>();
    keys.forEach(key -> edges.put(key.targetId(), stored.get(key.id())));
    return new RecordCollectionMutationPlan.Observations(
        source.get(command.sourceRecordId()), targets, edges);
  }

  private Map<String, Long> versions(
      RuntimeRecordModel model,
      RecordAccessContext access,
      List<String> ids,
      RecordValueCondition condition,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    final RecordSelectPlan plan;
    try {
      plan = compiler.referenceTargets(model, access, ids, condition, true);
    } catch (DnaRuntimeException error) {
      if (error.errorCode() == DnaRuntimeErrorCode.RECORD_QUERY_INVALID
          || error.errorCode() == DnaRuntimeErrorCode.RECORD_ACCESS_DENIED
          || error.errorCode() == DnaRuntimeErrorCode.RECORD_COMMAND_INVALID) {
        throw conflict();
      }
      throw error;
    }
    return versions(Set.copyOf(ids), reader.apply(plan));
  }

  static Map<String, Long> versions(Set<String> ids, CalciteQueryResult query) {
    if (query == null
        || query.truncated()
        || query.returnedRows() != query.rows().size()
        || query.rows().size() > ids.size()) {
      throw RecordReferenceAdmission.incompatible();
    }
    var found = new LinkedHashMap<String, Long>();
    for (var row : query.rows()) {
      if (row.size() != 2
          || !(row.getFirst() instanceof String id)
          || !ids.contains(id)
          || found.containsKey(id)
          || !(row.get(1) instanceof Number value)) {
        throw RecordReferenceAdmission.incompatible();
      }
      final long version;
      try {
        version = new BigDecimal(value.toString()).longValueExact();
      } catch (ArithmeticException | NumberFormatException invalid) {
        throw RecordReferenceAdmission.incompatible();
      }
      if (version < 0) {
        throw RecordReferenceAdmission.incompatible();
      }
      found.put(id, version);
    }
    if (!found.keySet().equals(ids)) {
      throw conflict();
    }
    return Map.copyOf(found);
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "The collection endpoints no longer satisfy the authorized relation policy");
  }
}
