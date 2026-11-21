package org.simplepoint.plugin.app.form.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCollectionMigration;

/** The immutable authoring decision, previous binding and one new policy transition. */
final class FormRelationConversion {
  private FormRelationConversion() {}

  static Set<String> retiredFields(ApplicationFormDocument document) {
    return document.relations().stream()
        .filter(FormRelationDefinition::retired)
        .map(relation -> relation.sourceField().fieldId())
        .collect(Collectors.toUnmodifiableSet());
  }

  static List<String> convertibleSources(ApplicationFormDocument published) {
    return published.relations().stream()
        .filter(
            relation ->
                !relation.retired()
                    && relation.cardinality() == FormRelationCardinality.MANY_TO_ONE
                    && relation.sourceField().detailId() == null)
        .map(FormRelationDefinition::relationId)
        .toList();
  }

  static Map<String, FormRelationBinding> freezeRetired(
      ApplicationFormDocument next,
      ApplicationFormDocument published,
      List<FormRelationBinding> previousBindings) {
    var former = published == null ? Map.<String, FormRelationDefinition>of() : byId(published);
    var desired = byId(next);
    for (var old : former.values()) {
      if (old.retired() && !old.equals(desired.get(old.relationId()))) {
        throw invalid("A converted source must retain its published identity");
      }
    }
    var result = new HashMap<String, FormRelationBinding>();
    boolean starting = false;
    for (var source : desired.values()) {
      if (!source.retired()) {
        continue;
      }
      var old = former.get(source.relationId());
      if (old == null) {
        throw invalid("Only an installed many-to-one relation can be converted");
      }
      if (!old.retired()) {
        String newId = source.retiredByCollectionId();
        var target = desired.get(newId);
        if (starting
            || old.cardinality() != FormRelationCardinality.MANY_TO_ONE
            || !old.sourceField().equals(source.sourceField())
            || !old.target().equals(source.target())
            || former.containsKey(newId)
            || target == null
            || target.cardinality() != FormRelationCardinality.MANY_TO_MANY
            || !source.target().applicationId().equals(target.target().applicationId())
            || !source.target().formId().equals(target.target().formId())) {
          throw invalid(
              "Conversion requires one published root reference and a new collection on the same"
                  + " form");
        }
        starting = true;
      }
      var binding =
          previousBindings.stream()
              .filter(
                  item ->
                      item.relationId().equals(source.relationId())
                          && item.target().equals(source.target()))
              .findFirst()
              .orElseThrow(() -> invalid("The published reference binding is unavailable"));
      result.put(source.relationId(), binding);
    }
    return Map.copyOf(result);
  }

  static RecordReferenceCollectionMigration command(
      ApplicationFormDocument document, RecordCalculationDefinition previous) {
    RecordReferenceCollectionMigration migration = null;
    for (var source : document.relations()) {
      if (!source.retired()) {
        continue;
      }
      var installed =
          previous == null
              ? null
              : previous.referencesOrEmpty().stream()
                  .filter(rule -> rule.relationId().equals(source.relationId()))
                  .findFirst()
                  .orElse(null);
      if (installed == null) {
        throw invalid("Conversion requires an installed source reference");
      }
      if (!installed.retired()) {
        if (migration != null) {
          throw invalid("Only one reference conversion is allowed per publication");
        }
        migration =
            new RecordReferenceCollectionMigration(
                source.relationId(), source.retiredByCollectionId());
      }
    }
    return migration;
  }

  private static Map<String, FormRelationDefinition> byId(ApplicationFormDocument document) {
    return document.relations().stream()
        .collect(
            Collectors.toUnmodifiableMap(FormRelationDefinition::relationId, Function.identity()));
  }

  private static ApplicationFormException invalid(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);
  }
}
