package org.simplepoint.plugin.app.form.service;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRebindMigration;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget;

/**
 * Resolves a one-time replacement from immutable APP definitions and the installed write policy.
 */
final class FormCollectionRebinding {
  private FormCollectionRebinding() {}

  static boolean validateVersion(ApplicationFormDocument next, ApplicationFormDocument published) {
    var former = published == null ? Map.<String, FormRelationDefinition>of() : byId(published);
    boolean starting = false;
    for (var successor : next.relations()) {
      var known = former.get(successor.relationId());
      if (known != null
          && known.cardinality() == FormRelationCardinality.MANY_TO_MANY
          && successor.cardinality() == FormRelationCardinality.MANY_TO_MANY
          && !sameTarget(known, successor)) {
        throw invalid("Changing the target form requires an explicit migration");
      }
      var replacement = successor.collectionReplacement();
      if (replacement == null) {
        continue;
      }
      if (known != null) {
        if (!replacement.equals(known.collectionReplacement())) {
          throw invalid("The published collection replacement mapping is immutable");
        }
        continue;
      }
      var source = former.get(replacement.sourceRelationId());
      if (starting
          || source == null
          || source.cardinality() != FormRelationCardinality.MANY_TO_MANY
          || sameTarget(source, successor)) {
        throw invalid("Replacement requires one published collection and a new target form");
      }
      starting = true;
    }
    return starting;
  }

  static RecordCollectionRebindMigration command(
      ApplicationFormDocument document, RecordCalculationDefinition previous) {
    var installed =
        previous == null
            ? java.util.Set.<String>of()
            : previous.collectionsOrEmpty().stream()
                .map(rule -> rule.relationId())
                .collect(Collectors.toUnmodifiableSet());
    RecordCollectionRebindMigration migration = null;
    for (var successor : document.relations()) {
      var replacement = successor.collectionReplacement();
      if (replacement == null || installed.contains(successor.relationId())) {
        continue;
      }
      if (migration != null || !installed.contains(replacement.sourceRelationId())) {
        throw invalid("Replacement requires exactly one currently installed source collection");
      }
      migration =
          new RecordCollectionRebindMigration(
              replacement.sourceRelationId(), successor.relationId(), replacement.targetRecords());
    }
    return migration;
  }

  static RecordSnapshotTarget entrance(RecordCollectionRule rule) {
    return new RecordSnapshotTarget(
        rule.targetModelId(), rule.targetAppId(), rule.targetFormId(), rule.targetResourceCode());
  }

  static java.util.List<FormRelationDefinition> publishedCollections(
      ApplicationFormDocument document) {
    return document.relations().stream()
        .filter(relation -> relation.cardinality() == FormRelationCardinality.MANY_TO_MANY)
        .map(
            relation ->
                relation.collectionReplacement() == null
                    ? relation
                    : new FormRelationDefinition(
                        "1",
                        relation.relationId(),
                        relation.cardinality(),
                        null,
                        relation.target(),
                        relation.qualification(),
                        relation.referenceView(),
                        relation.mappings(),
                        relation.collectionLabel()))
        .toList();
  }

  record Access(
      RecordCollectionRebindMigration migration,
      RecordSnapshotTarget source,
      RecordSnapshotTarget destination) {}

  private static boolean sameTarget(FormRelationDefinition left, FormRelationDefinition right) {
    return left.target().applicationId().equals(right.target().applicationId())
        && left.target().formId().equals(right.target().formId());
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
