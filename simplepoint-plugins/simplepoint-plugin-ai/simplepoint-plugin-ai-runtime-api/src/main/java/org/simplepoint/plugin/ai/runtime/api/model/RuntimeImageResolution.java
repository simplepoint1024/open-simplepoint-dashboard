package org.simplepoint.plugin.ai.runtime.api.model;

/** Content-addressed result of resolving one mutable OCI image reference. */
public record RuntimeImageResolution(
    String imageDigest,
    String admissionPolicyHash
) {
}
