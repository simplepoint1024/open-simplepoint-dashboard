package org.simplepoint.plugin.ai.runtime.api.model;

/** Replaces the editable portion of a Runtime Profile draft. */
public record RuntimeMcpProfileUpdateRequest(
    String name,
    RuntimeMcpProfileSpec spec
) {
}
