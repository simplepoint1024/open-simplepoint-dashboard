package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordStateTransitions;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;

/** Enforces installed lifecycle transitions using transaction-local prior and prospective values. */
final class RecordStateTransitionValidation {
  private final RecordStateTransitions definition;
  private final String column;

  RecordStateTransitionValidation(final Map<String, LogicalFieldDefinition> fields, final Set<String> outputs,
      final RecordStateTransitions definition) {
    this.definition = definition;
    if (definition == null) {
      column = null;
    } else {
      var field = fields.get(definition.fieldId());
      if (field == null || field.type() != LogicalFieldType.STRING || outputs.contains(definition.fieldId())
          || definition.transitions().keySet().stream().anyMatch(state -> state.length() > field.length())) {
        throw invalid("Lifecycle state must be a bounded ordinary string field");
      }
      column = field.code();
    }
  }

  void validateTarget(final Map<String, ?> values) {
    if (definition != null) {
      state(values);
    }
  }

  void validateTransition(final Map<String, ?> previous, final Map<String, ?> prospective) {
    if (definition == null) {
      return;
    }
    String next = state(prospective);
    var allowed = previous == null ? definition.initialStates() : definition.transitions().get(state(previous));
    if (!allowed.contains(next)) {
      throw invalid("The installed lifecycle does not allow this state transition");
    }
  }

  private String state(final Map<String, ?> values) {
    Object value = values.get(column);
    if (!(value instanceof String state) || !definition.transitions().containsKey(state)) {
      throw invalid("Record lifecycle state is absent or invalid");
    }
    return state;
  }

  private static DnaRuntimeException invalid(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, message);
  }
}
