package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.LogicalValueInputs;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

/** Real logical schemas with renamed columns and exact values shared by unit and target-DB tests. */
final class RecordPublicationPatchFixture {
  static final String PARENT = "00000000-0000-4000-8000-000000000011";
  static final String CHILD = "00000000-0000-4000-8000-000000000012";
  static final Instant BEFORE = Instant.parse("2026-10-17T02:03:04.654321Z");
  static final Instant AT = Instant.parse("2026-10-18T02:03:04.123456Z");
  static final RecordWriteFenceKey KEY = new RecordWriteFenceKey("tenant-a", "patch-parent");
  static final String CHILD_MODEL = "patch-child";
  final PhysicalSchemaCodec codec = new PhysicalSchemaCodec(new ObjectMapper());
  final RecordSqlCompiler sql = new RecordSqlCompiler(new StandardLogicalValueConverter());
  final RecordPublicationPatchCompiler compiler = new RecordPublicationPatchCompiler(codec, sql);
  final RuntimeRecordModel parent;
  final RuntimeRecordModel child;
  final Map<String, RecordPublicationPatchCompiler.Resolved> models;
  final RecordPublicationIntent intent;
  final RecordPublicationAggregate aggregate;

  RecordPublicationPatchFixture(String catalog, String schema) {
    var fields = List.of(field("amount", "total_amount", LogicalFieldType.DECIMAL, 38, 4),
        field("text", "summary", LogicalFieldType.TEXT, null, null), field("number", "units", LogicalFieldType.INT64, null, null),
        field("instant", "calculated_at", LogicalFieldType.DATETIME, null, null), field("date", "business_date", LogicalFieldType.DATE, null, null),
        field("time", "start_time", LogicalFieldType.TIME, null, null), field("flag", "enabled", LogicalFieldType.BOOLEAN, null, null),
        field("json", "payload", LogicalFieldType.JSON, null, null), field("binary", "bytes_value", LogicalFieldType.BINARY, null, null),
        field("uuid", "reference_id", LogicalFieldType.UUID, null, null),
        new LogicalFieldDefinition("source", "source_value", "Source", LogicalFieldType.STRING, false, 32, null, null, null));
    var detailFields = List.of(field("amount", "line_amount", LogicalFieldType.DECIMAL, 24, 2),
        new LogicalFieldDefinition("owner", "owner_record_id", "Parent", LogicalFieldType.UUID, false, null, null, null, null),
        new LogicalFieldDefinition("ordinal", "row_order", "Ordinal", LogicalFieldType.INT32, false, null, null, null, null),
        new LogicalFieldDefinition("memo", "memo", "Memo", LogicalFieldType.STRING, false, 64, null, null, null));
    parent = model(KEY.modelId(), "parent-form", "patch_parents", catalog, schema, fields, null);
    child = model(CHILD_MODEL, "child-form", "patch_children", catalog, schema, detailFields, new OwnedModelRelation(KEY.modelId(), "owner"));
    models = Map.of(KEY.modelId(), new RecordPublicationPatchCompiler.Resolved(parent, 7),
        CHILD_MODEL, new RecordPublicationPatchCompiler.Resolved(child, 8));
    var formulas = Map.ofEntries(
        Map.entry("amount", "9999999999999999999999999999999999.9999"),
        Map.entry("text", literal("候选值・日本語😀")), Map.entry("number", "-9223372036854775808"),
        Map.entry("instant", "NOW()"), Map.entry("date", "TODAY()"), Map.entry("time", literal("00:00:00.123456")),
        Map.entry("flag", "TRUE"), Map.entry("json", literal("{\"a\":1.25,\"文\":\"日\"}")),
        Map.entry("binary", literal("/w")), Map.entry("uuid", literal(CHILD)));
    var definition = new RecordCalculationDefinition("1", formulas,
        Map.of("lines", new RecordCalculationScope(CHILD_MODEL, reference(child, false), Map.of("amount", "20.02"))));
    var command = new RecordWritePolicyPublicationCommand(KEY.modelId(), "patch-app", "parent-form", reference(parent, false),
        0, 1, "patch-publication", "b".repeat(64), Map.of(), 120_000, Map.of(), definition);
    intent = new RecordPublicationIntent(1, KEY, command, new RecordRequestFingerprint(new ObjectMapper()).of(command),
        "0".repeat(64), "publisher", AT.toString(), Map.of(KEY.modelId(), binding(parent, 7), CHILD_MODEL, binding(child, 8)), null);
    var changes = new LinkedHashMap<String, RecordPublicationAggregate.Change>();
    changes.put("amount", change(LogicalValueKind.DECIMAL, "1234567890123456789012345678901234.1234", "9999999999999999999999999999999999.9999"));
    changes.put("text", change(LogicalValueKind.STRING, "原值・日本語😀", "候选值・日本語😀"));
    changes.put("number", change(LogicalValueKind.INTEGER, "9223372036854775807", "-9223372036854775808"));
    changes.put("instant", change(LogicalValueKind.DATETIME, BEFORE.toString(), AT.toString()));
    changes.put("date", change(LogicalValueKind.DATE, "2026-10-17", "2026-10-18"));
    changes.put("time", change(LogicalValueKind.TIME, "23:59:59.654321", "00:00:00.123456"));
    changes.put("flag", change(LogicalValueKind.BOOLEAN, "false", "true"));
    changes.put("json", change(LogicalValueKind.JSON, "{\"a\":1,\"文\":\"汉\"}", "{\"a\":1.25,\"文\":\"日\"}"));
    changes.put("binary", change(LogicalValueKind.BINARY, "+/8", "/w"));
    changes.put("uuid", new RecordPublicationAggregate.Change(LogicalValue.nullValue(), new LogicalValue(LogicalValueKind.UUID, CHILD)));
    aggregate = new RecordPublicationAggregate(1, PARENT, List.of(row(KEY.modelId(), PARENT, null, changes),
        row(CHILD_MODEL, CHILD, PARENT, Map.of("amount", change(LogicalValueKind.DECIMAL, "10.01", "20.02")))));
  }

  void verifyCalculatedCandidates() {
    var engine = new RecordCalculationCompiler(codec, java.time.Clock.fixed(AT, java.time.ZoneOffset.UTC));
    var evaluator = new RecordCalculationEvaluator(engine, parent, intent.command().calculation(), Map.of("lines", child));
    var result = evaluator.evaluate(Map.of("source_value", "保持不变・日本語"), Map.of("lines", List.of(Map.of("memo", "保持不变・日本語"))));
    var values = new StandardLogicalValueConverter();
    for (var row : aggregate.rows()) {
      var model = models.get(row.modelId()).model();
      var calculated = row.parentRecordId() == null ? result.parent() : result.details().get("lines").getFirst();
      for (var change : row.changes().entrySet()) {
        var field = model.activeSchema().fields().stream().filter(item -> item.fieldId().equals(change.getKey())).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(values.normalize(field, calculated.get(field.code()))).isEqualTo(change.getValue().candidate());
      }
    }
  }

  private static String literal(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  List<RecordSqlStatement> seed() {
    var statements = new ArrayList<RecordSqlStatement>();
    for (var row : aggregate.rows()) {
      var model = models.get(row.modelId()).model();
      var input = new LinkedHashMap<String, Object>();
      for (var field : model.requestedSchema().fields()) {
        var change = row.changes().get(field.fieldId());
        if (change != null) input.put(field.code(), LogicalValueInputs.fromCanonical(change.previous()));
        else input.put(field.code(), switch (field.fieldId()) {
          case "owner" -> PARENT;
          case "ordinal" -> 3;
          default -> "保持不变・日本語";
        });
      }
      // Public DTO forbids null map values. Omitted nullable fields become physical NULL.
      input.values().removeIf(java.util.Objects::isNull);
      var codes = Set.copyOf(model.requestedFieldCodes());
      var access = new RecordAccessContext(new RuntimeActor(KEY.tenantId(), "seed-actor"), "resource", codes, codes, Set.of(),
          new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "seed-actor", Set.of()), "department-a");
      statements.add(sql.mutation(model, access, new RecordCreateMutation("seed", input), BEFORE, row.recordId()));
    }
    return statements;
  }

  RecordPublicationAggregate.Row row(String model, String id, String owner, Map<String, RecordPublicationAggregate.Change> changes) {
    return new RecordPublicationAggregate.Row(model, id, owner, 0, "seed-actor", BEFORE.toString(), changes);
  }

  private RuntimeRecordModel model(String id, String form, String table, String catalog, String schema,
      List<LogicalFieldDefinition> fields, OwnedModelRelation owner) {
    var active = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, fields, List.of(), owner);
    var previous = fields.stream().map(field -> new LogicalFieldDefinition(field.fieldId(),
        field.code().equals("row_order") ? field.code() : "old_" + field.code(), field.displayName(), field.type(), field.nullable(),
        field.length(), field.precision(), field.scale(), field.defaultValue())).toList();
    return new RuntimeRecordModel(id, "patch-app", form, 2, active, 1,
        new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, previous, List.of(), owner),
        new PhysicalSchemaTarget(id, new RuntimeMetadataTarget("source", catalog, schema, table)));
  }

  RecordSchemaReference reference(RuntimeRecordModel model, boolean active) {
    return new RecordSchemaReference(active ? model.activeVersion() : model.requestedVersion(),
        codec.encode(active ? model.activeSchema() : model.requestedSchema()).checksum());
  }

  RecordPublicationIntent.Binding binding(RuntimeRecordModel model, long generation) {
    return new RecordPublicationIntent.Binding(reference(model, true), reference(model, false), model.target().metadataTarget(), generation);
  }

  private static LogicalFieldDefinition field(String id, String code, LogicalFieldType type, Integer precision, Integer scale) {
    return new LogicalFieldDefinition(id, code, code, type, true, null, precision, scale, null);
  }

  static RecordPublicationAggregate.Change change(LogicalValueKind kind, String before, String after) {
    return new RecordPublicationAggregate.Change(new LogicalValue(kind, before), new LogicalValue(kind, after));
  }
}
