package org.simplepoint.plugin.ai.skill.api.model;

import java.time.Instant;
import java.util.Map;

/**
 * Verified, immutable content resolved from one OCI Skill Artifact.
 *
 * @param digest OCI manifest digest
 * @param mediaType OCI artifact type
 * @param configDigest OCI config descriptor digest
 * @param contentDigest Skill Manifest layer digest
 * @param manifest decoded Skill Manifest layer
 * @param signatureRequired whether the active policy required a signature
 * @param signatureVerified whether Cosign admitted the Artifact
 * @param policyHash immutable supply-chain policy hash
 * @param verifiedAt verification time
 */
public record VerifiedSkillArtifact(
    String digest,
    String mediaType,
    String configDigest,
    String contentDigest,
    Map<String, Object> manifest,
    boolean signatureRequired,
    boolean signatureVerified,
    String policyHash,
    Instant verifiedAt
) {
}
