package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFieldWriteRule;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;

/** Compiled write admission is shared by preview, single-record and owned aggregate mutations. */
final class RecordFieldWriteValidation {
  private final Map<String, Map<String, RecordFieldWriteRule>> scopes = new LinkedHashMap<>();
  private final String stateColumn;

  RecordFieldWriteValidation(final Map<String, Map<String, LogicalFieldDefinition>> fields,
      final RecordCalculationDefinition definition) {
    var lifecycle = definition.stateTransitions();
    var state = lifecycle == null ? null : fields.getOrDefault("parent", Map.of()).get(lifecycle.fieldId());
    if (lifecycle != null && state == null) {
      throw invalid("Field write lifecycle is unavailable");
    }
    stateColumn = state == null ? null : state.code();
    for (var rule : definition.writeRulesOrEmpty()) {
      var target = rule.field();
      var field = fields.getOrDefault(target.scope(), Map.of()).get(target.fieldId());
      var formulas = "parent".equals(target.scope()) ? definition.formulas()
          : definition.details().containsKey(target.scope()) ? definition.details().get(target.scope()).formulas() : Map.of();
      if (field == null || lifecycle == null || !lifecycle.transitions().keySet().containsAll(rule.updateStates())
          || formulas.containsKey(target.fieldId()) || "parent".equals(target.scope()) && lifecycle.fieldId().equals(target.fieldId())) {
        throw invalid("Field write admission does not match the installed ordinary fields and lifecycle");
      }
      scopes.computeIfAbsent(target.scope(), ignored -> new LinkedHashMap<>()).put(field.code(), rule);
    }
  }

  void validate(final String scope, final Map<String, ?> patch, final Map<String, ?> previousParent) {
    var rules = scopes.getOrDefault(scope, Map.of());
    for (String column : patch.keySet()) {
      var rule = rules.get(column);
      if (rule != null && !(previousParent == null ? rule.create()
          : previousParent.get(stateColumn) instanceof String state && rule.updateStates().contains(state))) {
        throw invalid("The installed field policy does not permit this write");
      }
    }
  }

  private static DnaRuntimeException invalid(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, message);
  }
}
