package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;

class RecordPublicationCollectionBindingsTest {
  private static final String ROOT = RecordPublicationFixtures.KEY.modelId();
  private final RecordPublicationIntent legacy =
      RecordPublicationFixtures.intent(RecordPublicationFixtures.KEY);

  @Test
  void journalsCollectionsSeparatelyAndPreservesOldWireShapes() throws Exception {
    var mapper = new ObjectMapper();
    assertThat(mapper.writeValueAsString(legacy))
        .doesNotContain("collectionBindings", "collections");
    var intent = intent(3, Map.of("members", binding("source", "edges", 0)));
    assertThat(intent.bindings()).containsOnlyKeys(ROOT);
    assertThat(intent.collectionBindings()).containsOnlyKeys("members");
    assertThat(mapper.readValue(mapper.writeValueAsString(intent), RecordPublicationIntent.class))
        .isEqualTo(intent);
    assertThat(
            intent
                .withQueryProtection(Map.of(ROOT, java.util.Set.of("label")))
                .collectionBindings())
        .isEqualTo(intent.collectionBindings());
  }

  @Test
  void rejectsIncompleteExtraAliasedForeignStorageAndLegacyGenerationBindings() {
    for (var entries :
        List.of(
            Map.<String, RecordPublicationIntent.Binding>of(),
            Map.of("other", binding("source", "edges", 0)),
            Map.of("members", binding("source", "records", 0)),
            Map.of("members", binding("foreign", "edges", 0)),
            Map.of("members", binding("source", "edges", 1)))) {
      assertThatThrownBy(() -> intent(3, entries)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> intent(2, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> intent(3, null)).isInstanceOf(IllegalArgumentException.class);
  }

  private RecordPublicationIntent intent(
      int version, Map<String, RecordPublicationIntent.Binding> edges) {
    var original = legacy.command();
    var rule =
        new RecordCollectionRule(
            "1",
            "members",
            "edge-model",
            RecordPublicationFixtures.SCHEMA,
            "target",
            "app",
            "target-form",
            "target-page",
            null,
            null,
            null);
    var definition =
        new RecordCalculationDefinition(
            "1", Map.of(), Map.of(), null, null, null, null, null, List.of(rule));
    var command =
        new RecordWritePolicyPublicationCommand(
            ROOT,
            "app",
            "form",
            original.schemaReference(),
            0,
            1,
            original.publicationKey(),
            original.policyChecksum(),
            Map.of(),
            30_000,
            Map.of(),
            definition);
    return new RecordPublicationIntent(
        version,
        legacy.key(),
        command,
        new RecordRequestFingerprint(new ObjectMapper()).of(command),
        legacy.previousFingerprint(),
        legacy.actorId(),
        legacy.evaluatedAt(),
        Map.of(ROOT, binding("source", "records", 0)),
        null,
        Map.of(ROOT, Map.of()),
        Map.of(),
        edges);
  }

  private static RecordPublicationIntent.Binding binding(
      String store, String table, long generation) {
    return new RecordPublicationIntent.Binding(
        RecordPublicationFixtures.SCHEMA,
        RecordPublicationFixtures.SCHEMA,
        new RuntimeMetadataTarget(store, null, "forms", table),
        generation);
  }
}
