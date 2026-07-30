package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Signed claims that authorize exactly one Skill Workflow Tool call.
 *
 * @param issuer trusted control-plane issuer
 * @param audience intended MCP Gateway audience
 * @param tokenId random, single-use nonce
 * @param issuedAtEpochSecond issue time
 * @param expiresAtEpochSecond expiry time
 * @param operation exact allowed operation
 * @param scopeType platform or tenant execution scope
 * @param tenantId tenant identifier when scopeType is TENANT
 * @param subjectId original execution requester
 * @param agentVersionId optional Agent version for the future Agent chain
 * @param skillId immutable Skill definition identifier
 * @param skillVersionId immutable Skill version identifier
 * @param executionId durable Skill execution identifier
 * @param stepId durable Skill step identifier
 * @param serverId pinned MCP Server identifier
 * @param snapshotId pinned MCP capability snapshot identifier
 * @param target exact Tool name, Prompt name, or resolved Resource URI
 * @param maximumCalls maximum calls authorized by this token
 * @param maximumRequestBytes maximum serialized Tool argument bytes
 * @param maximumResultBytes maximum serialized Tool result bytes
 */
public record McpSkillCapabilityClaims(
    String issuer,
    String audience,
    String tokenId,
    long issuedAtEpochSecond,
    long expiresAtEpochSecond,
    String operation,
    String scopeType,
    String tenantId,
    String subjectId,
    String agentVersionId,
    String skillId,
    String skillVersionId,
    String executionId,
    String stepId,
    String serverId,
    String snapshotId,
    String target,
    int maximumCalls,
    long maximumRequestBytes,
    long maximumResultBytes
) {
}
