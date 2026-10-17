package org.simplepoint.plugin.app.form.api.model;

/** Idempotent request to publish one immutable form version to an application page. */
public record PublishApplicationFormCommand(
    int formVersion,
    String pageId,
    String idempotencyKey
) {
}
