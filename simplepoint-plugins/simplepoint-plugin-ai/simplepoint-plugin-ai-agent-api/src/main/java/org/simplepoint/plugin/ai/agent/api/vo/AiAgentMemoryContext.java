package org.simplepoint.plugin.ai.agent.api.vo;

import java.util.List;

/**
 * Pinned long-term memory context injected into one Agent execution.
 *
 * @param memories exact retrieved memories
 * @param contextJson canonical durable snapshot
 * @param instructions bounded untrusted-context envelope
 * @param injectedCharacters actual injected character count
 * @param snapshotHash SHA-256 of the canonical snapshot
 */
public record AiAgentMemoryContext(
    List<AiAgentMemory> memories,
    String contextJson,
    String instructions,
    int injectedCharacters,
    String snapshotHash
) {
}
