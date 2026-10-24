package org.simplepoint.plugin.app.form.api.model;

/** Acknowledged batch progress; decimal strings preserve exact counters in browser clients. */
public record ApplicationFormPublicationProgress(
    int contractVersion, Phase phase, String sequence, String completedRecords, String totalRecords) {

  /** All phases are nonterminal, including a phase whose completed count equals its total. */
  public enum Phase {
    STAGING, APPLYING, RESTORING
  }
}
