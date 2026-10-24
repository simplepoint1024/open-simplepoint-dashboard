package org.simplepoint.plugin.dna.runtime.service.record;

import com.google.re2j.Pattern;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.simplepoint.calculation.FormulaException;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFieldConstraints;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.service.support.LogicalValueInputs;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

/** Compiled, model-bound business validation shared by ordinary inputs and calculated outputs. */
final class RecordCalculationConstraints {
  private final Map<String, Map<String, Rule>> scopes;

  RecordCalculationConstraints(final Map<String, Map<String, LogicalFieldDefinition>> fields,
      final RecordCalculationDefinition definition) {
    Map<String, Map<String, Rule>> compiled = new LinkedHashMap<>();
    compiled.put("parent", compile(fields.get("parent"), definition.constraintsOrEmpty()));
    definition.details().forEach((scope, declared) -> compiled.put(scope, compile(fields.get(scope), declared.constraintsOrEmpty())));
    scopes = Map.copyOf(compiled);
  }

  Object validate(final String scope, final String id, final Object typed) {
    var rule = scopes.get(scope).get(id);
    if (rule != null) {
      rule.validate(typed);
    }
    return typed;
  }

  private static Map<String, Rule> compile(final Map<String, LogicalFieldDefinition> fields,
      final Map<String, RecordFieldConstraints> definitions) {
    if (!fields.keySet().containsAll(definitions.keySet())) {
      throw invalid("Business constraints reference an unknown or managed field", null);
    }
    Map<String, Rule> rules = new LinkedHashMap<>();
    definitions.forEach((id, constraint) -> rules.put(id, compile(fields.get(id), constraint)));
    return Map.copyOf(rules);
  }

  static Rule compile(final LogicalFieldDefinition field, final RecordFieldConstraints constraints) {
    boolean text = field.type() == LogicalFieldType.STRING || field.type() == LogicalFieldType.TEXT;
    int capacity = field.type() == LogicalFieldType.STRING ? field.length() : 1_048_576;
    if (!text && (constraints.minLength() != null || constraints.maxLength() != null || constraints.pattern() != null)
        || constraints.minLength() != null && constraints.minLength() > capacity) {
      throw invalid("Text constraints do not match the physical field", null);
    }
    if (constraints.selection() != null && field.type() != LogicalFieldType.JSON) {
      throw invalid("Selection domains require a JSON field", null);
    }
    try {
      Pattern pattern = constraints.pattern() == null ? null : Pattern.compile(constraints.pattern());
      if (pattern != null && pattern.programSize() > 4096) {
        throw invalid("Validation pattern exceeds the program budget", null);
      }
      Set<Object> choices = new LinkedHashSet<>();
      var converter = new StandardLogicalValueConverter();
      for (var declared : constraints.choices()) {
        Object input = LogicalValueInputs.fromCanonical(declared);
        if (converter.normalize(field, input).kind() != declared.kind()
            || !choices.add(RecordCalculationValues.typed(field, input))) {
          throw invalid("Scalar choices have invalid types or duplicate semantic values", null);
        }
      }
      return new Rule(constraints.minLength(), constraints.maxLength(), capacity, pattern, Set.copyOf(choices),
          constraints.selection() == null ? null : new RecordJsonSelectionRule(constraints.selection()));
    } catch (DnaRuntimeException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw invalid("Business constraint definition is invalid", failure);
    }
  }

  private static DnaRuntimeException invalid(final String message, final Throwable failure) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, message, failure);
  }

  record Rule(Integer minLength, Integer maxLength, int capacity, Pattern pattern, Set<Object> choices, RecordJsonSelectionRule selection) {
    void validate(final Object value) {
      // Nullability remains the physical field contract; submission-only requiredness is a lifecycle rule.
      if (value == null) {
        return;
      }
      // Match platform optional-text semantics; an empty value has no length/pattern violation.
      if (value instanceof String text && !text.isEmpty()) {
        int length = text.codePointCount(0, text.length());
        if (length > capacity || minLength != null && length < minLength || maxLength != null && length > maxLength
            || pattern != null && !pattern.matcher(text).find()) {
          throw new FormulaException("RESULT_VALIDATION");
        }
      }
      if (!choices.isEmpty() && !choices.contains(value)) {
        throw new FormulaException("RESULT_CHOICE");
      }
      if (selection != null) {
        selection.validate(value);
      }
    }
  }
}
