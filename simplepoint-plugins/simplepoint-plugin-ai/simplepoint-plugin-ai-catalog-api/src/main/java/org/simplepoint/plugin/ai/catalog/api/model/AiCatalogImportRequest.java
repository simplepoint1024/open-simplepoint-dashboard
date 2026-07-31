package org.simplepoint.plugin.ai.catalog.api.model;

/**
 * Optional local identity overrides when importing an official MCP Server.
 */
public record AiCatalogImportRequest(String code, String name) {
}
