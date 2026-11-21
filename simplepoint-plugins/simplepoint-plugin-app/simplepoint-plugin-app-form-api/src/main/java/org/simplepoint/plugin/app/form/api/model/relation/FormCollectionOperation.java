package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.List;

/** Browser commands use logical page/record identities and observed revisions. */
public final class FormCollectionOperation {
  private FormCollectionOperation() {}

  public record Selection(String memberId, List<String> members, boolean link) {
    public Selection {
      members = members == null ? List.of() : List.copyOf(members);
    }
  }

  public record Edge(String memberId, Long memberVersion, Long edgeVersion, boolean linked) {}

  public record Observation(long anchorVersion, List<Edge> edges) {
    public Observation {
      edges = List.copyOf(edges);
    }
  }

  public record Command(Long anchorVersion, List<Edge> edges, boolean link, String idempotencyKey) {
    public Command {
      edges = edges == null ? List.of() : List.copyOf(edges);
    }
  }
}
