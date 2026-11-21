package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.List;
import org.simplepoint.api.locale.LocalizedText;

/** Authorized owning relations that point back to the form being designed; no physical metadata. */
public record FormInverseSourceMetadata(
    FormRelationTarget source, int formVersion, List<Relation> relations) {
  /** Retains an immutable, bounded selection supplied by the authorized catalog. */
  public FormInverseSourceMetadata {
    relations = List.copyOf(relations);
  }

  /** Stable relation identity and localized source label for one bounded designer choice. */
  public record Relation(
      String relationId,
      String label,
      LocalizedText labelLocalization,
      String detailLabel,
      LocalizedText detailLabelLocalization,
      boolean nullable,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
          boolean collection) {
    public Relation(String relationId, String label, LocalizedText labelLocalization, String detailLabel,
        LocalizedText detailLabelLocalization, boolean nullable) {
      this(relationId, label, labelLocalization, detailLabel, detailLabelLocalization, nullable, false);
    }
  }
}
