package org.simplepoint.plugin.dna.runtime.api.record;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Complete incremental collection outcome, including observed no-op and retained edge versions. */
public record CollectionRecordResult(
    String contractVersion,
    String sourceRecordId,
    Long sourceVersion,
    List<Edge> edges,
    Instant evaluatedAt) {
  /** The result is canonical and bounded independently of its underlying SQL statement count. */
  public CollectionRecordResult {
    sourceRecordId = CollectionEdgeMutation.canonicalRecordId(sourceRecordId);
    Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    edges = List.copyOf(edges);
    var identities = new HashSet<String>();
    if (!"1".equals(contractVersion)
        || sourceVersion == null
        || sourceVersion < 0
        || !evaluatedAt.equals(evaluatedAt.truncatedTo(ChronoUnit.MICROS))
        || edges.isEmpty()
        || edges.size() > 100
        || edges.stream().anyMatch(edge -> !identities.add(edge.targetRecordId()))) {
      throw new IllegalArgumentException("Invalid collection result");
    }
    edges = edges.stream().sorted(Comparator.comparing(Edge::targetRecordId)).toList();
  }

  /** Unchanged edges are explicit; -1 can only represent an unchanged, never-created edge. */
  public record Edge(
      String targetRecordId,
      Long targetVersion,
      Long edgeVersion,
      Boolean linked,
      Boolean changed) {
    /** Missing JSON observations cannot masquerade as version zero or an unlinked edge. */
    public Edge {
      targetRecordId = CollectionEdgeMutation.canonicalRecordId(targetRecordId);
      if (targetVersion == null
          || targetVersion < 0
          || edgeVersion == null
          || edgeVersion < -1
          || linked == null
          || changed == null
          || edgeVersion == -1 && (linked || changed)) {
        throw new IllegalArgumentException("Invalid collection edge result");
      }
    }
  }
}
