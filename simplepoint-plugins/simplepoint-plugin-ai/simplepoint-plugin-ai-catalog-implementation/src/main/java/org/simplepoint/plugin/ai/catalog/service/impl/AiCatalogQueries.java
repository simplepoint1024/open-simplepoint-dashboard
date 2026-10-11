package org.simplepoint.plugin.ai.catalog.service.impl;

import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.core.schema.ProjectionTableSchema;
import org.simplepoint.data.jpa.ProjectionPages;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogItem;
import org.simplepoint.plugin.ai.catalog.entity.AiCatalogReadModel;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/** Query adapter for the scoped internal and official catalog union. */
@Component
public class AiCatalogQueries {
  private final EntityManager em;
  private final AiScopeAccessPolicy scopePolicy;

  /** Creates the adapter with its management-scope policy. */
  public AiCatalogQueries(EntityManager em, AiScopeAccessPolicy scopePolicy) {
    this.em = em;
    this.scopePolicy = scopePolicy;
  }

  /** Returns the mixed-source scalar query contract. */
  public Map<String, Object> schema() {
    scopePolicy.currentManagementScope();
    return ProjectionTableSchema.schema(
        AiCatalogItem.class,
        "ai.catalog.column",
        Map.of(
            "source",
                List.of(
                    new ProjectionTableSchema.Choice("INTERNAL", "i18n:ai.catalog.source.internal"),
                    new ProjectionTableSchema.Choice(
                        "OFFICIAL_MCP", "i18n:ai.catalog.source.official")),
            "kind", List.of(new ProjectionTableSchema.Choice("MCP_SERVER", "MCP Server"))));
  }

  /** Filters and sorts the combined catalog before database pagination. */
  public Page<AiCatalogReadModel> page(Map<String, String> parameters, Pageable page) {
    var scope = scopePolicy.currentManagementScope();
    var filters = new LinkedHashMap<>(parameters == null ? Map.<String, String>of() : parameters);
    String keyword = filters.remove("query");
    String search = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    if (search.length() > 200) {
      throw new InvalidQueryException("Catalog query must not exceed 200 characters");
    }
    Set<String> searchFields = Set.of("title", "description", "code", "registryName");
    if (!search.isEmpty()) {
      searchFields.forEach(
          field -> QueryCapabilities.require(AiCatalogItem.class, field, "like", false));
    }
    String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    return ProjectionPages.query(
        em,
        AiCatalogReadModel.class,
        AiCatalogItem.class,
        filters,
        page,
        "updatedAt",
        searchFields,
        (cb, query, row) ->
            cb.and(
                cb.or(
                    cb.equal(row.get("source"), "OFFICIAL_MCP"),
                    cb.and(
                        cb.equal(row.get("source"), "INTERNAL"),
                        cb.equal(row.get("scopeType"), scope.scopeType().name()),
                        scope.tenantId() == null
                            ? cb.isNull(row.get("tenantId"))
                            : cb.equal(row.get("tenantId"), scope.tenantId()))),
                search.isEmpty()
                    ? cb.conjunction()
                    : cb.or(
                        searchFields.stream()
                            .map(field -> cb.like(cb.lower(row.get(field)), pattern, '!'))
                            .toArray(jakarta.persistence.criteria.Predicate[]::new))),
        row -> row);
  }
}
