package org.simplepoint.plugin.ai.runtime.api.model;

/** Admission material used to publish an immutable Runtime revision. */
public record RuntimeMcpProfilePublishRequest(
    String imageDigest,
    String admissionReportHash
) {
}
