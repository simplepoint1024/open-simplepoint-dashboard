package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Explicit desired replica update for a runtime pool.
 */
public record RuntimePoolScaleRequest(int desiredReplicas) {
}
