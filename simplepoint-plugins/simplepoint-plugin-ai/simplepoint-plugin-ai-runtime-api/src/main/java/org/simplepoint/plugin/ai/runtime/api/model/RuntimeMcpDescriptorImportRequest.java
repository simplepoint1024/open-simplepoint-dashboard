package org.simplepoint.plugin.ai.runtime.api.model;

/** Immutable upstream descriptor material accepted from the Catalog domain. */
public record RuntimeMcpDescriptorImportRequest(
    String sourceCatalogEntryId,
    String registryName,
    String serverVersion,
    String title,
    String repositoryUrl,
    String descriptorJson
) {
}
