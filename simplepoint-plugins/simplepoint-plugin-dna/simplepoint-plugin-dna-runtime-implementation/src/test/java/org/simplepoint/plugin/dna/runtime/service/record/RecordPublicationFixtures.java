package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;

final class RecordPublicationFixtures {
  static final RecordWriteFenceKey KEY = new RecordWriteFenceKey("tenant-a", "publication-model");
  static final Instant AT = Instant.parse("2026-10-17T10:00:00.123456Z");
  static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));

  private RecordPublicationFixtures() {
  }

  static RecordPublicationStore.Entry entry(long total) {
    return new RecordPublicationStore.Entry(intent(KEY), RecordPublicationCheckpoint.start(total));
  }

  static RecordPublicationIntent intent(RecordWriteFenceKey key) {
    var command = new RecordWritePolicyPublicationCommand(key.modelId(), "app", "form", SCHEMA,
        0, 1, "publish-key", "b".repeat(64), Map.of(), 10_000, Map.of(),
        new RecordCalculationDefinition("1", Map.of("result", "CONCAT(\"中文・日本語😀\",[label])"), Map.of()));
    return new RecordPublicationIntent(1, key, command, new RecordRequestFingerprint(new ObjectMapper()).of(command),
        "0".repeat(64), "publisher", AT.toString(), Map.of(key.modelId(), binding("source")), null);
  }

  static RecordPublicationIntent.Binding binding(String source) {
    return new RecordPublicationIntent.Binding(SCHEMA, SCHEMA,
        new RuntimeMetadataTarget(source, "database", "forms", "records"), 7);
  }
}
