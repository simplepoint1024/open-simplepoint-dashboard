package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;

class RecordCollectionFingerprintTest {
  private final RecordRequestFingerprint fingerprints =
      new RecordRequestFingerprint(new ObjectMapper());

  @Test
  void canonicalOrderAndTimeoutDoNotChangeIntentButEveryObservedVersionDoes() {
    var first = command(0, "key", 30_000, edge(2, 0, -1), edge(3, 1, 0));
    var reordered = command(0, "another-key", 60_000, edge(3, 1, 0), edge(2, 0, -1));
    assertThat(fingerprints.of(first)).isEqualTo(fingerprints.of(reordered));
    for (var changed :
        List.of(
            command(1, "key", 30_000, edge(2, 0, -1), edge(3, 1, 0)),
            command(0, "key", 30_000, edge(2, 1, -1), edge(3, 1, 0)),
            command(0, "key", 30_000, edge(2, 0, 1), edge(3, 1, 0)),
            command(0, "key", 30_000, edge(2, 0, -1)),
            command(
                0,
                "key",
                30_000,
                new CollectionEdgeMutation(id(2), 0L, -1L, CollectionEdgeMutation.Mode.UNLINK),
                edge(3, 1, 0)))) {
      assertThat(fingerprints.of(changed)).isNotEqualTo(fingerprints.of(first));
    }
  }

  private static CollectionRecordCommand command(
      long version, String key, int timeout, CollectionEdgeMutation... mutations) {
    return new CollectionRecordCommand(
        "1",
        "projects",
        new RecordSchemaReference(1, "a".repeat(64)),
        "members",
        id(1),
        version,
        List.of(mutations),
        key,
        timeout);
  }

  private static CollectionEdgeMutation edge(int id, long targetVersion, long edgeVersion) {
    return new CollectionEdgeMutation(
        id(id), targetVersion, edgeVersion, CollectionEdgeMutation.Mode.LINK);
  }

  private static String id(int value) {
    return new UUID(0, value).toString();
  }
}
