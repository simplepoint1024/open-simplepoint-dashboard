package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Graceful shutdown notification from one concrete runtime generation.
 *
 * @param instanceId current runtime process generation
 */
public record RuntimeNodeOfflineRequest(String instanceId) {
}
