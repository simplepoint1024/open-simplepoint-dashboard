package org.simplepoint.plugin.app.form.api.model;

/** Idempotent request to prepare one immutable form version in DNA. */
public record PrepareApplicationFormDataCommand(
    int formVersion,
    String idempotencyKey
) {
}
