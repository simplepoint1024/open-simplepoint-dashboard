package org.simplepoint.plugin.dna.runtime.service.record;

import com.google.re2j.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;

/** Checks already authorized captured values before the physical transaction can commit. */
final class RecordSnapshotValidation {
  private static final Set<LogicalFieldType> TEXT_WIRE = Set.of(LogicalFieldType.STRING, LogicalFieldType.TEXT,
      LogicalFieldType.UUID, LogicalFieldType.DATE, LogicalFieldType.TIME, LogicalFieldType.DATETIME);
  private final List<Rule> rules;

  RecordSnapshotValidation(final RuntimeRecordModel model, final RecordAccessContext access,
      final List<RecordSnapshotFieldRequirement> requirements) {
    List<Rule> compiled = new ArrayList<>();
    for (var requirement : requirements == null ? List.<RecordSnapshotFieldRequirement>of() : requirements) {
      var field = model.requestedSchema().fields().stream().filter(value -> value.fieldId().equals(requirement.fieldId()))
          .findFirst().orElseThrow(() -> invalid("Snapshot requirement references an unknown field"));
      if (!access.readableFields().contains(field.code()) || access.maskedFields().contains(field.code())) {
        throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
            "A required capture projection is unavailable in the active field scope");
      }
      if (!TEXT_WIRE.contains(field.type()) && (requirement.maxTextUnits() != null || requirement.pattern() != null
          || requirement.nonBlank() || requirement.trimmed() || requirement.noControls())) {
        throw invalid("Text capture requirements require a textual wire field");
      }
      Pattern pattern;
      try {
        pattern = requirement.pattern() == null ? null : Pattern.compile(requirement.pattern());
      } catch (com.google.re2j.PatternSyntaxException failure) {
        throw invalid("Invalid snapshot requirement pattern");
      }
      if (pattern != null && pattern.programSize() > 4096) {
        throw invalid("Snapshot requirement pattern exceeds its budget");
      }
      compiled.add(new Rule(field.code(), requirement, pattern));
    }
    rules = List.copyOf(compiled);
  }

  void validate(final Map<String, Object> values) {
    for (var rule : rules) {
      if (!values.containsKey(rule.column())) {
        throw invalid("The captured field projection is incomplete");
      }
      Object value = values.get(rule.column());
      var requirement = rule.requirement();
      if (requirement.required() && (value == null || value instanceof String text && text.isBlank()
          || value instanceof List<?> list && list.isEmpty())) {
        throw invalid("The captured result does not satisfy its required value contract");
      }
      if (value instanceof String text && (requirement.maxTextUnits() != null && text.length() > requirement.maxTextUnits()
          || requirement.nonBlank() && text.isBlank() || requirement.trimmed() && !text.equals(text.trim())
          || requirement.noControls() && text.codePoints().anyMatch(Character::isISOControl)
          || rule.pattern() != null && !rule.pattern().matcher(text).matches())) {
        throw invalid("The captured result does not satisfy its value format contract");
      }
    }
  }

  private static DnaRuntimeException invalid(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, message);
  }

  private record Rule(String column, RecordSnapshotFieldRequirement requirement, Pattern pattern) {
  }
}
