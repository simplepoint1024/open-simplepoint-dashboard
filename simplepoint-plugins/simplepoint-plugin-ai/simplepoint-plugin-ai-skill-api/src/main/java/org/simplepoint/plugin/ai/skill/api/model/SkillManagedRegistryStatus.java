package org.simplepoint.plugin.ai.skill.api.model;

import java.time.Instant;

/**
 * Public, credential-free view of the managed Skill OCI Registry.
 *
 * @param configured whether managed publishing is explicitly configured
 * @param registry Registry host without scheme or credentials
 * @param repositoryPrefix platform-owned repository prefix
 * @param secureTransport whether TLS is required for Registry traffic
 * @param authenticationConfigured whether server-side credentials are present
 * @param signatureRequired whether publication admission requires a signature
 * @param connected result of an explicit connectivity check, otherwise null
 * @param checkedAt time of the explicit connectivity check, otherwise null
 * @param message stable operator-facing connectivity summary
 */
public record SkillManagedRegistryStatus(
    boolean configured,
    String registry,
    String repositoryPrefix,
    boolean secureTransport,
    boolean authenticationConfigured,
    boolean signatureRequired,
    Boolean connected,
    Instant checkedAt,
    String message
) {
}
