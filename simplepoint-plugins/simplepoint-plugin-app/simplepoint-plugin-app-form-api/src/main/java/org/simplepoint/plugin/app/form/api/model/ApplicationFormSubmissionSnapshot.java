package org.simplepoint.plugin.app.form.api.model;

/** Authorized immutable business evidence rendered with its original form version. */
public record ApplicationFormSubmissionSnapshot(
    ApplicationFormSubmissionView submission,
    ApplicationFormRuntimeView form,
    ApplicationFormRecordView record) {}
