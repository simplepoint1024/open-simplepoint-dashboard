package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordResult;

/**
 * Complete collection mutations and one typed result, independent of ordinary receipt counts.
 * Observations must come from authorized reads repeated under the complete transaction row locks.
 * This compiler does not grant access or replace installed-rule/lifecycle admission.
 */
record RecordCollectionMutationPlan(
    List<RecordSqlStatement> statements, CollectionRecordResult result) {
  RecordCollectionMutationPlan {
    statements = List.copyOf(statements);
    if (statements.size() > 201 || result == null) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  /** Include unchanged endpoints and absent edges; never discover another row after locking. */
  static RecordSequenceLockPlan locks(
      RecordCollectionBinding binding,
      CollectionRecordCommand command,
      String tenant,
      Runnable verifyReads) {
    requireBinding(binding, command);
    var sources = new LinkedHashSet<RecordSequenceLockPlan.Source>();
    sources.add(
        new RecordSequenceLockPlan.Source(command.sourceModelId(), command.sourceRecordId()));
    for (var mutation : command.mutations()) {
      sources.add(
          new RecordSequenceLockPlan.Source(
              binding.target().physicalModelId(), mutation.targetRecordId()));
      var edge =
          RecordCollectionEdgeKey.of(
              binding, tenant, command.sourceRecordId(), mutation.targetRecordId());
      sources.add(new RecordSequenceLockPlan.Source(binding.edge().physicalModelId(), edge.id()));
    }
    return new RecordSequenceLockPlan(List.copyOf(sources), List.of(), verifyReads);
  }

  static RecordCollectionMutationPlan prepare(
      RecordCollectionBinding binding,
      CollectionRecordCommand command,
      RecordAccessContext sourceAccess,
      RecordAccessContext targetAccess,
      Observations observed,
      Instant at,
      RecordSqlCompiler compiler) {
    requireBinding(binding, command);
    if (!sourceAccess.actor().equals(targetAccess.actor())) {
      throw RecordReferenceAdmission.incompatible();
    }
    Set<String> targets =
        command.mutations().stream()
            .map(value -> value.targetRecordId())
            .collect(Collectors.toSet());
    if (!observed.targetVersions().keySet().equals(targets)
        || !observed.edges().keySet().equals(targets)
        || observed.sourceVersion() != command.expectedSourceVersion()) {
      throw conflict();
    }
    var edges = new ArrayList<RecordSqlStatement>();
    var outcomes = new ArrayList<CollectionRecordResult.Edge>();
    var touches = new ArrayList<RecordSqlStatement>();
    for (var mutation : command.mutations()) {
      String targetId = mutation.targetRecordId();
      boolean self = sameRoot(binding, command, targetId);
      if (!observed.targetVersions().get(targetId).equals(mutation.expectedTargetVersion())
          || self && !mutation.expectedTargetVersion().equals(command.expectedSourceVersion())) {
        throw conflict();
      }
      var state = observed.edges().get(targetId);
      var next = state.transition(mutation.expectedEdgeVersion(), mutation.mode());
      boolean changed = !state.equals(next);
      if (changed) {
        var key =
            RecordCollectionEdgeKey.of(
                binding, sourceAccess.actor().tenantId(), command.sourceRecordId(), targetId);
        edges.add(RecordCollectionSql.change(binding, key, state, mutation, sourceAccess, at));
        if (!self) {
          touches.add(
              compiler.touchCollectionEndpoint(
                  binding.target(), targetAccess, targetId, mutation.expectedTargetVersion(), at));
        }
      }
      outcomes.add(
          new CollectionRecordResult.Edge(
              targetId,
              mutation.expectedTargetVersion() + (changed ? 1 : 0),
              next.version(),
              next.active(),
              changed));
    }
    var statements = new ArrayList<RecordSqlStatement>();
    if (!edges.isEmpty()) {
      statements.add(
          compiler.touchCollectionEndpoint(
              binding.source(),
              sourceAccess,
              command.sourceRecordId(),
              command.expectedSourceVersion(),
              at));
      statements.addAll(touches);
      statements.addAll(edges);
      // A no-op self edge still observes the source revision advanced by another changed edge.
      outcomes.replaceAll(
          outcome ->
              sameRoot(binding, command, outcome.targetRecordId())
                  ? new CollectionRecordResult.Edge(
                      outcome.targetRecordId(),
                      command.expectedSourceVersion() + 1,
                      outcome.edgeVersion(),
                      outcome.linked(),
                      outcome.changed())
                  : outcome);
    }
    var result =
        new CollectionRecordResult(
            "1",
            command.sourceRecordId(),
            command.expectedSourceVersion() + (edges.isEmpty() ? 0 : 1),
            outcomes,
            at);
    RecordCollectionReceipt.requireCommand(
        command, RecordCollectionReceipt.accepted(binding, result));
    return new RecordCollectionMutationPlan(statements, result);
  }

  private static boolean sameRoot(
      RecordCollectionBinding binding, CollectionRecordCommand command, String targetId) {
    return binding.source().physicalModelId().equals(binding.target().physicalModelId())
        && command.sourceRecordId().equals(targetId);
  }

  private static void requireBinding(
      RecordCollectionBinding binding, CollectionRecordCommand command) {
    if (!binding.source().physicalModelId().equals(command.sourceModelId())
        || !binding.relationId().equals(command.relationId())) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "The collection endpoints or observed edges changed");
  }

  /**
   * Target identifiers key both maps; a filtered/missing target is never treated as version zero.
   */
  record Observations(
      long sourceVersion,
      Map<String, Long> targetVersions,
      Map<String, RecordCollectionEdgeState> edges) {
    Observations {
      targetVersions = Map.copyOf(targetVersions);
      edges = Map.copyOf(edges);
      if (sourceVersion < 0 || targetVersions.values().stream().anyMatch(version -> version < 0)) {
        throw conflict();
      }
    }
  }
}
