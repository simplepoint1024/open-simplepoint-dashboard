package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** One versioned and replayable selection from a published page. */
public record DeleteApplicationFormRecordsCommand(
    ApplicationFormRuntimeIdentity runtime, String idempotencyKey, List<RecordRevision> records) {
  public record RecordRevision(String id, Long expectedVersion) {}
}
