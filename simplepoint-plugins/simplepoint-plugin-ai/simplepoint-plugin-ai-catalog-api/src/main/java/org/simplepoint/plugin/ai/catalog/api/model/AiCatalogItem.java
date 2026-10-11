package org.simplepoint.plugin.ai.catalog.api.model;

import java.time.Instant;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/** Normalized extension package presented by the workbench. */
@org.simplepoint.core.query.TableQuery(
    value = {
      @org.simplepoint.core.query.QueryField("title"),
      @org.simplepoint.core.query.QueryField("description"),
      @org.simplepoint.core.query.QueryField("code"),
      @org.simplepoint.core.query.QueryField("registryName"),
      @org.simplepoint.core.query.QueryField("source"),
      @org.simplepoint.core.query.QueryField("kind"),
      @org.simplepoint.core.query.QueryField("version"),
      @org.simplepoint.core.query.QueryField("status"),
      @org.simplepoint.core.query.QueryField("updatedAt")
    },
    maxPageSize = 100)
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
    boolean remoteImportable,
    boolean runtimeImportable,
    Instant updatedAt) {}
