package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Validates both outgoing declarations and incoming retained-version dependencies. */
public final class ReferenceFieldDependencies {
  private ReferenceFieldDependencies() {}

  public static void requireCompatible(
      LogicalReferenceDefinition reference, PhysicalSchemaDefinition target) {
    var fields =
        target.fields().stream()
            .collect(Collectors.toMap(LogicalFieldDefinition::fieldId, Function.identity()));
    for (var dependency : reference.targetFields()) {
      var field = fields.get(dependency.fieldId());
      if (field == null || field.type() != dependency.type()) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.SCHEMA_INVALID,
            "A retained business reference requires a compatible target field");
      }
    }
  }
}
