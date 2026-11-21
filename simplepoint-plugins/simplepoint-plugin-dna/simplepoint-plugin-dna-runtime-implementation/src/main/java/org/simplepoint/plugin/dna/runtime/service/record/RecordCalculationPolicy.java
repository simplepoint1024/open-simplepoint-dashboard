package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;

/** Storage contract for a trusted definition, distinct from an ordinary record command. */
record RecordCalculationPolicy(
    int contractVersion,
    String appId,
    String formId,
    RecordSchemaReference parentSchema,
    RecordCalculationDefinition definition,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Set<String>> queryProtection,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Set<String> retiredRelations) {

  RecordCalculationPolicy(
      int contractVersion,
      String appId,
      String formId,
      RecordSchemaReference parentSchema,
      RecordCalculationDefinition definition,
      Map<String, Set<String>> queryProtection) {
    this(contractVersion, appId, formId, parentSchema, definition, queryProtection, Set.of());
  }

  RecordCalculationPolicy(
      int contractVersion,
      String appId,
      String formId,
      RecordSchemaReference parentSchema,
      RecordCalculationDefinition definition) {
    this(contractVersion, appId, formId, parentSchema, definition, Map.of());
  }

  RecordCalculationPolicy {
    if (contractVersion != 1 || !identity(appId) || !identity(formId)) {
      throw new IllegalArgumentException("Invalid stored calculation policy identity or version");
    }
    Objects.requireNonNull(parentSchema, "parentSchema");
    Objects.requireNonNull(definition, "definition");
    queryProtection = RecordQueryProtection.immutable(queryProtection);
    retiredRelations =
        retiredRelations == null
            ? Set.of()
            : Collections.unmodifiableSet(new TreeSet<>(retiredRelations));
    if (retiredRelations.stream().anyMatch(id -> !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
        || !Collections.disjoint(
            retiredRelations,
            definition.collectionsOrEmpty().stream().map(rule -> rule.relationId()).toList())
        || !Collections.disjoint(
            retiredRelations,
            definition.referencesOrEmpty().stream().map(rule -> rule.relationId()).toList())) {
      throw new IllegalArgumentException("Invalid retired collection identities");
    }
  }

  private static boolean identity(final String value) {
    return value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.trim());
  }
}
