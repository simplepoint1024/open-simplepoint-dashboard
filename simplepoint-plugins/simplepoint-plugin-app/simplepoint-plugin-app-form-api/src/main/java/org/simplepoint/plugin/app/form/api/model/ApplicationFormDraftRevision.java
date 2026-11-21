package org.simplepoint.plugin.app.form.api.model;

/** An exact catalog selection for deleting a form draft. */
public record ApplicationFormDraftRevision(String id, Long expectedRevision) {}
