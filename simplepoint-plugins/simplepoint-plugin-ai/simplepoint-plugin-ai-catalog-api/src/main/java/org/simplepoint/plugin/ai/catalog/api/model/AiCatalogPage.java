package org.simplepoint.plugin.ai.catalog.api.model;

import java.util.List;

/**
 * Stable page contract for the mixed-source catalog.
 */
public record AiCatalogPage(
    List<AiCatalogItem> content,
    int number,
    int size,
    long totalElements,
    int totalPages
) {
}
