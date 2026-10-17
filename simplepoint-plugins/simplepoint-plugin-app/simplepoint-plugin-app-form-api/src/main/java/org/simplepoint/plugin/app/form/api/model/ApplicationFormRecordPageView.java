package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** One authorized page of records for an active published form version. */
public record ApplicationFormRecordPageView(
    int page,
    int size,
    long total,
    List<ApplicationFormRecordView> records
) {

  /** Keeps page content immutable. */
  public ApplicationFormRecordPageView {
    records = records == null ? List.of() : List.copyOf(records);
  }
}
