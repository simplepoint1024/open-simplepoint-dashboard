package org.simplepoint.plugin.app.form.api.model;

/** Effective page action capabilities derived from platform authorization. */
public record ApplicationFormRuntimeCapabilities(
    boolean canCreate,
    boolean canEdit,
    boolean canDelete,
    boolean canStartProcess
) {
}
