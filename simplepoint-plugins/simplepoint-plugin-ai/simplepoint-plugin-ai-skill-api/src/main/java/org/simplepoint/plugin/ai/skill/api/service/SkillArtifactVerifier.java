package org.simplepoint.plugin.ai.skill.api.service;

import org.simplepoint.plugin.ai.skill.api.model.VerifiedSkillArtifact;

/**
 * Resolves and verifies an immutable OCI Skill Artifact.
 */
public interface SkillArtifactVerifier {

  /**
   * Pulls the Artifact and applies content and signature policy.
   *
   * @param artifactReference OCI repository reference with tag or digest
   * @param expectedDigest expected OCI manifest sha256 digest
   * @return authoritative content resolved from the Registry
   */
  VerifiedSkillArtifact verify(
      String artifactReference,
      String expectedDigest
  );
}
