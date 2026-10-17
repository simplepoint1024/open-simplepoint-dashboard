package org.simplepoint.plugin.app.form.api.model;

/** Immutable operation acknowledgement plus an optional, separately authorized current record. */
public record ApplicationFormRecordMutationView(
    String recordId,
    long version,
    FormRecordState state,
    ApplicationFormRecordView currentRecord
) {
}
