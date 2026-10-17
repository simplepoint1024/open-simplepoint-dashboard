package org.simplepoint.plugin.app.form.api.model;

/** APP catalog projection used to resolve an active runtime page. */
public record ApplicationPageBindingTarget(
    String pageId,
    String pageName,
    String formId,
    int formVersion,
    String resourceCode
) {
}
