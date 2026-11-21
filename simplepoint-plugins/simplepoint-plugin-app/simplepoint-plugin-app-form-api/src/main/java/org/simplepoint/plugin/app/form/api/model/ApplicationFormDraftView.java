package org.simplepoint.plugin.app.form.api.model;

import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;

/** Current mutable design snapshot and its optimistic revision. */
public record ApplicationFormDraftView(
    String formId,
    long revision,
    ApplicationFormDocument document,
    String checksum,
    String updatedBy,
    Instant updatedAt,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        List<String> convertibleRelationIds,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        List<FormRelationDefinition> publishedCollections) {
  public ApplicationFormDraftView(
      String formId,
      long revision,
      ApplicationFormDocument document,
      String checksum,
      String updatedBy,
      Instant updatedAt) {
    this(formId, revision, document, checksum, updatedBy, updatedAt, List.of(), List.of());
  }

  public ApplicationFormDraftView {
    convertibleRelationIds =
        convertibleRelationIds == null ? List.of() : List.copyOf(convertibleRelationIds);
    publishedCollections = publishedCollections == null ? List.of() : List.copyOf(publishedCollections);
  }
}
