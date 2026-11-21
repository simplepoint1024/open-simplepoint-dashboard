package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;

/** Current endpoint revisions and edge observations; retained edges keep their last revision. */
public record CollectionRecordObservationView(long sourceVersion, List<Edge> edges) {
  public CollectionRecordObservationView {
    edges = List.copyOf(edges);
  }

  public record Edge(String targetRecordId, long targetVersion, long edgeVersion, boolean linked) {}
}
