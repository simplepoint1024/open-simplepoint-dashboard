package org.simplepoint.plugin.ai.catalog.api.model;

/** Selects one official package and creates an editable Runtime Profile draft. */
public record AiCatalogRuntimeImportRequest(
    String code,
    String name,
    Integer packageIndex
) {
}
