package org.simplepoint.plugin.app.form.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Permission-filtered prospective values by stable field/detail identities. Detail positions
 * match the proposed collection; this projection carries no new record IDs or commit receipt.
 */
public record ApplicationFormRecordPreviewView(
    ApplicationFormRuntimeIdentity runtime,
    Map<String, Object> values,
    Map<String, List<Map<String, Object>>> details) {

  /** Copies nullable values and ordered collections. */
  public ApplicationFormRecordPreviewView {
    Objects.requireNonNull(runtime, "runtime");
    values = copy(values);
    Map<String, List<Map<String, Object>>> collections = new LinkedHashMap<>();
    details.forEach((id, rows) -> collections.put(id, rows.stream().map(ApplicationFormRecordPreviewView::copy).toList()));
    details = Collections.unmodifiableMap(collections);
  }

  private static Map<String, Object> copy(final Map<String, Object> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
