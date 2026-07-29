package org.simplepoint.plugin.ai.skill.api.model;

import java.util.Map;

/**
 * Creates one immutable, digest-pinned Skill Artifact version.
 *
 * @param version semantic version
 * @param artifactReference OCI repository and tag reference
 * @param artifactDigest immutable OCI manifest digest
 * @param manifest expected SimplePoint Skill Manifest; it must match the
 *                 authoritative OCI Artifact layer
 */
public record SkillVersionCreateRequest(
    String version,
    String artifactReference,
    String artifactDigest,
    Map<String, Object> manifest
) {
}
