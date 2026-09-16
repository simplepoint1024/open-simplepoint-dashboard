package org.simplepoint.plugin.ai.skill.service.artifact;

/** Immutable result of pushing one generated Skill Artifact. */
public record PushedSkillOciArtifact(
    String artifactReference,
    String artifactDigest,
    String configDigest,
    String contentDigest,
    String contentHash
) {
}
