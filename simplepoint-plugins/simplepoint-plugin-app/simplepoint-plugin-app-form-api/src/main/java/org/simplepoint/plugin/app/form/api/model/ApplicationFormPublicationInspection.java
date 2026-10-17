package org.simplepoint.plugin.app.form.api.model;

import java.time.Instant;
import java.util.List;

/** Aggregate-only publication feedback; counts are decimal strings to preserve browser precision. */
public record ApplicationFormPublicationInspection(Instant checkedAt, List<Issue> issues, String repairPageId) {
  /** Copies inspection rows for safe transport. */
  public ApplicationFormPublicationInspection {
    issues = List.copyOf(issues);
  }

  /** Each row describes an independent check; counts must not be summed across overlapping checks. */
  public record Issue(String id, Kind kind, List<String> fields, String count) {
    /** Copies field labels without including any record values. */
    public Issue {
      fields = List.copyOf(fields);
    }
  }

  /** Stable user-facing categories of publication failures. */
  public enum Kind {
    REQUIRED, INVALID_VALUE
  }
}
