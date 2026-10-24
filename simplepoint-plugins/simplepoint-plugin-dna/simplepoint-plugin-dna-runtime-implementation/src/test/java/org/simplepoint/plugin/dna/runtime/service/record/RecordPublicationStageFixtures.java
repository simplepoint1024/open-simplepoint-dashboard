package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

final class RecordPublicationStageFixtures {
  static final String PARENT = "00000000-0000-4000-8000-000000000001";
  static final String SECOND = "00000000-0000-4000-8000-000000000002";
  static final RecordWriteFenceKey KEY = new RecordWriteFenceKey("tenant-a", "staging-model");
  static final String CHILD = "staging-detail";

  private RecordPublicationStageFixtures() {
  }

  static RecordPublicationIntent intent() {
    var original = RecordPublicationFixtures.intent(KEY);
    var definition = new RecordCalculationDefinition("1", Map.of("result", "[input]", "amount", "1", "integer", "1",
        "date", "TODAY()", "time", "[input]", "instant", "NOW()", "flag", "TRUE", "nullable", "NULL"),
        Map.of("lines", new RecordCalculationScope(CHILD, RecordPublicationFixtures.SCHEMA, Map.of("amount", "1"))));
    var old = original.command();
    var command = new RecordWritePolicyPublicationCommand(KEY.modelId(), old.appId(), old.formId(), old.schemaReference(),
        old.expectedVersion(), old.targetVersion(), old.publicationKey(), old.policyChecksum(), old.checks(), old.timeoutMs(),
        old.valueChecks(), definition);
    var bindings = new LinkedHashMap<>(original.bindings());
    // MySQL has a catalog and no schema; the frozen intent must support this legitimate placement.
    bindings.put(KEY.modelId(), new RecordPublicationIntent.Binding(RecordPublicationFixtures.SCHEMA, RecordPublicationFixtures.SCHEMA,
        new RuntimeMetadataTarget("source", "database", null, "parents"), 7));
    bindings.put(CHILD, new RecordPublicationIntent.Binding(RecordPublicationFixtures.SCHEMA, RecordPublicationFixtures.SCHEMA,
        new RuntimeMetadataTarget("source", "database", null, "children"), 7));
    return new RecordPublicationIntent(1, KEY, command, new RecordRequestFingerprint(new ObjectMapper()).of(command),
        original.previousFingerprint(), original.actorId(), original.evaluatedAt(), bindings, null);
  }

  static RecordPublicationAggregate aggregate(String parent, int repeat) {
    var changes = new LinkedHashMap<String, RecordPublicationAggregate.Change>();
    changes.put("result", change(LogicalValueKind.STRING, "原值・日本語😀".repeat(repeat), "新值・日本語😀"));
    changes.put("amount", change(LogicalValueKind.DECIMAL, "1234567890123456789012345678901234.1234", "9999999999999999999999999999999999.9999"));
    changes.put("integer", change(LogicalValueKind.INTEGER, "9223372036854775807", "-9223372036854775808"));
    changes.put("date", change(LogicalValueKind.DATE, "2026-10-17", "2026-10-18"));
    changes.put("time", change(LogicalValueKind.TIME, "23:59:59.123456", "00:00:00.000001"));
    changes.put("instant", change(LogicalValueKind.DATETIME, "2026-10-17T10:00:00.123456Z", "2026-10-18T10:00:00.000001Z"));
    changes.put("flag", change(LogicalValueKind.BOOLEAN, "false", "true"));
    changes.put("nullable", new RecordPublicationAggregate.Change(LogicalValue.nullValue(), new LogicalValue(LogicalValueKind.STRING, "")));
    return new RecordPublicationAggregate(1, parent, List.of(row(KEY.modelId(), parent, null, changes),
        row(CHILD, UUID.nameUUIDFromBytes(parent.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), parent,
            Map.of("amount", change(LogicalValueKind.DECIMAL, "10.10", "12.12")))));
  }

  static RecordPublicationAggregate.Row row(String model, String id, String parent, Map<String, RecordPublicationAggregate.Change> changes) {
    return new RecordPublicationAggregate.Row(model, id, parent, 19, "original-updater", "2026-10-16T20:00:00.654321Z", changes);
  }

  static RecordPublicationAggregate.Change change(LogicalValueKind kind, String previous, String candidate) {
    return new RecordPublicationAggregate.Change(new LogicalValue(kind, previous), new LogicalValue(kind, candidate));
  }
}
