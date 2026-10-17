package org.simplepoint.plugin.app.form.api.model;

/** Atomic APP catalog update after a managed runtime resource is active. */
public record ActivateApplicationPageBinding(
    String tenantId,
    String applicationId,
    String pageId,
    String formId,
    int formVersion,
    String resourceId,
    String resourceCode,
    String runtimePath
) {
}
