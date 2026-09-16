package org.simplepoint.plugin.ai.runtime.api.model;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Immutable deployment snapshot linking one descriptor and runtime profile to
 * an admitted OCI artifact.
 */
public record RuntimeMcpDeploymentRevisionSpec(
    String revisionId,
    String descriptorContentHash,
    RuntimeMcpProfileSpec profile,
    String profileHash,
    String imageDigest,
    String admissionReportHash,
    Instant createdAt
) {

  private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
  private static final Pattern OCI_DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");

  /** Validates immutable hashes and OCI digest pinning. */
  public RuntimeMcpDeploymentRevisionSpec {
    revisionId = required(revisionId, "Runtime revision ID");
    descriptorContentHash = hash(descriptorContentHash, "Descriptor content hash");
    if (profile == null) {
      throw new IllegalArgumentException("Runtime profile is required");
    }
    profileHash = hash(profileHash, "Runtime profile hash");
    if (profile.artifact().type() == RuntimeMcpProfileSpec.ArtifactType.OCI) {
      imageDigest = required(imageDigest, "OCI image digest");
      if (!OCI_DIGEST.matcher(imageDigest).matches()) {
        throw new IllegalArgumentException("OCI image digest must be sha256 pinned");
      }
    } else if (imageDigest != null) {
      throw new IllegalArgumentException(
          "Image digest is valid only for OCI deployment revisions"
      );
    }
    if (admissionReportHash != null) {
      admissionReportHash = hash(admissionReportHash, "Admission report hash");
    }
    if (createdAt == null) {
      throw new IllegalArgumentException("Runtime revision creation time is required");
    }
  }

  private static String hash(final String value, final String label) {
    String normalized = required(value, label);
    if (!SHA256.matcher(normalized).matches()) {
      throw new IllegalArgumentException(label + " must be a lowercase SHA-256 hash");
    }
    return normalized;
  }

  private static String required(final String value, final String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value.trim();
  }
}
