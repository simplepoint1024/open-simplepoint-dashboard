package org.simplepoint.plugin.app.form.api.model;

/** Replaces a form draft using optimistic revision control. */
public record SaveApplicationFormDraftCommand(
    long expectedRevision,
    ApplicationFormDocument document
) {
}
