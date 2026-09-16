package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * One control-plane-resolved storage mount sent to a Tool Runtime node.
 *
 * @param type bounded runtime storage class
 * @param source platform-derived Docker volume name, null for ephemeral mounts
 * @param targetPath absolute container target
 * @param readOnly whether the workload receives read-only access
 * @param sizeBytes optional tmpfs/ephemeral size limit
 */
public record RuntimeStorageMount(
    String type,
    String source,
    String targetPath,
    boolean readOnly,
    Long sizeBytes
) {
}
