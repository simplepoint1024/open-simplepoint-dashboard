package org.simplepoint.plugin.ai.runtime.api.model;

/** Creates a scope-owned descriptor and an editable Runtime Profile draft. */
public record RuntimeMcpProfileImportRequest(
    String code,
    String name,
    RuntimeMcpDescriptorImportRequest descriptor,
    RuntimeMcpProfileSpec spec
) {
}
