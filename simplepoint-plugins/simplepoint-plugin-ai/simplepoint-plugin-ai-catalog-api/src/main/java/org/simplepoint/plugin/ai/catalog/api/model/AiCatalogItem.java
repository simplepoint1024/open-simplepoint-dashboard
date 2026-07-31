package org.simplepoint.plugin.ai.catalog.api.model;

import java.time.Instant;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Normalized extension package presented by the workbench.
 */
public record AiCatalogItem(
    String id,
    CatalogSource source,
    CatalogPackageKind kind,
    String registryName,
    String code,
    String version,
    String title,
    String description,
    String status,
    AiResourceScope scopeType,
    String endpointUrl,
    String repositoryUrl,
    String websiteUrl,
    boolean importable,
    Instant updatedAt
) {
}
