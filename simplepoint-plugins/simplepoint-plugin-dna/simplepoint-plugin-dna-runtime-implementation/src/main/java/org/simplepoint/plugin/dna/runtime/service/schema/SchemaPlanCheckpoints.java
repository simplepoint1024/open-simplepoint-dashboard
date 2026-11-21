package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalColumnSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalIndexSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;

/** Predicts exact statement boundaries; never derives a recovery baseline from a changed live table. */
final class SchemaPlanCheckpoints {
  private SchemaPlanCheckpoints() {
  }

  static InitialSchemaPlan attach(InitialSchemaPlan plan, PhysicalSchemaDefinition previous,
      boolean alterIncludesComment, PhysicalSchemaSnapshotCodec codec) {
    PhysicalTableSnapshot state = plan.actualSnapshot();
    List<String> checksums = new ArrayList<>();
    checksums.add(state.checksum());
    for (var step : plan.steps()) {
      if (step.sequence() != checksums.size()) throw invalid();
      state = apply(state, plan.targetSnapshot(), previous, step, alterIncludesComment, codec);
      checksums.add(state.checksum());
    }
    if (!state.checksum().equals(plan.targetSnapshot().checksum())) throw invalid();
    return new InitialSchemaPlan(plan.dialectId(), plan.database(), plan.actualSnapshot(),
        plan.targetSnapshot(), plan.risk(), plan.steps(), checksums);
  }

  private static PhysicalTableSnapshot apply(PhysicalTableSnapshot before, PhysicalTableSnapshot target,
      PhysicalSchemaDefinition previous, PlannedSchemaStep step, boolean alterIncludesComment,
      PhysicalSchemaSnapshotCodec codec) {
    Map<String, PhysicalColumnSnapshot> columns = before.columns().stream().collect(Collectors.toMap(
        PhysicalColumnSnapshot::name, Function.identity(), (left, right) -> {throw invalid();}, LinkedHashMap::new));
    Map<String, PhysicalIndexSnapshot> indexes = before.indexes().stream().collect(Collectors.toMap(
        PhysicalIndexSnapshot::name, Function.identity(), (left, right) -> {throw invalid();}, LinkedHashMap::new));
    Map<String, PhysicalForeignKeySnapshot> foreignKeys = before.foreignKeys().stream().collect(Collectors.toMap(
        PhysicalForeignKeySnapshot::name, Function.identity(), (left, right) -> {throw invalid();}, LinkedHashMap::new));
    String key = step.satisfactionKey();
    String comment = before.tableComment();
    boolean exists = before.exists();
    switch (step.operation()) {
      case CREATE_TABLE -> {
        if (!exists) {
          exists = true;
          target.columns().forEach(column -> columns.put(column.name(), withComment(column, null)));
          target.indexes().stream().filter(PhysicalIndexSnapshot::primary).forEach(index -> indexes.put(index.name(), index));
        }
      }
      case ADD_COLUMN -> {
        var column = withComment(column(target, key), null);
        if (columns.putIfAbsent(key, column) != null) throw invalid();
      }
      case ALTER_COLUMN -> {
        var old = columns.get(key);
        if (old == null) throw invalid();
        var replacement = column(target, key);
        columns.put(key, withComment(replacement, alterIncludesComment ? replacement.comment() : old.comment()));
      }
      case RENAME_COLUMN -> {
        if (previous == null) throw invalid();
        String oldName = previous.fields().stream().filter(field -> field.fieldId().equals(step.logicalObject()))
            .findFirst().orElseThrow(SchemaPlanCheckpoints::invalid).code();
        var old = columns.remove(oldName);
        if (old == null || columns.containsKey(key) || foreignKeys.values().stream().anyMatch(reference -> reference.columns().contains(oldName))) throw invalid();
        columns.put(key, new PhysicalColumnSnapshot(key, old.storageType(), old.length(), old.precision(),
            old.scale(), old.nullable(), old.defaultValue(), old.comment()));
        indexes.replaceAll((name, index) -> new PhysicalIndexSnapshot(name,
            index.columns().stream().map(value -> value.equals(oldName) ? key : value).toList(), index.unique(), index.primary()));
      }
      case DROP_COLUMN -> {
        columns.remove(key);
        indexes.values().removeIf(index -> index.columns().contains(key));
      }
      case CREATE_INDEX -> {
        var desired = target.indexes().stream().filter(index -> index.name().equals(key))
            .findFirst().orElseThrow(SchemaPlanCheckpoints::invalid);
        var old = indexes.putIfAbsent(key, desired);
        if (old != null && !old.equals(desired)) throw invalid();
      }
      case DROP_INDEX -> indexes.remove(key);
      case DROP_FOREIGN_KEY -> foreignKeys.remove(key);
      case ADD_FOREIGN_KEY -> {
        var desired = target.foreignKeys().stream().filter(reference -> reference.name().equals(key)).findFirst()
            .orElseThrow(SchemaPlanCheckpoints::invalid);
        var old = foreignKeys.putIfAbsent(key, desired);
        if (old != null && !old.equals(desired)) throw invalid();
      }
      case COMMENT_TABLE -> comment = target.tableComment();
      case COMMENT_COLUMN -> {
        var old = columns.get(key);
        if (old == null) throw invalid();
        var desired = column(target, key);
        columns.put(key, withComment(alterIncludesComment ? desired : old, desired.comment()));
      }
    }
    return codec.snapshot(exists, List.copyOf(columns.values()), List.copyOf(indexes.values()), comment, List.copyOf(foreignKeys.values()));
  }

  private static PhysicalColumnSnapshot column(PhysicalTableSnapshot snapshot, String name) {
    return snapshot.columns().stream().filter(column -> column.name().equals(name)).findFirst()
        .orElseThrow(SchemaPlanCheckpoints::invalid);
  }

  private static PhysicalColumnSnapshot withComment(PhysicalColumnSnapshot column, String comment) {
    return new PhysicalColumnSnapshot(column.name(), column.storageType(), column.length(), column.precision(),
        column.scale(), column.nullable(), column.defaultValue(), comment);
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_INVALID,
        "Generated schema steps do not describe a complete deterministic structure transition");
  }
}
