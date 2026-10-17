package org.simplepoint.plugin.app.form.service;

/** Server-only immutable child schema identity attached to a published form version. */
public record FormDetailBinding(
    String detailId, String physicalModelId, int schemaVersion, String checksum) {}
