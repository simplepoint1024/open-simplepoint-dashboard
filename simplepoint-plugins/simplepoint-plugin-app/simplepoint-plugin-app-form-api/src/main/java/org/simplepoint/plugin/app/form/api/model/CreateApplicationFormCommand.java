package org.simplepoint.plugin.app.form.api.model;

/** Creates one form and its initial main-scene draft under an application. */
public record CreateApplicationFormCommand(
    String applicationId,
    String code,
    String name,
    String description
) {
}
