package org.simplepoint.plugin.ai.mcp.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.ProjectionQuery;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpCapabilitySnapshot;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilitySnapshotSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** Query immutable snapshots inside a server already authorized by the MCP domain service. */
@Component
public class McpSnapshotQueries {
  private final EntityManager em;

  /** Creates the scope-bound snapshot query adapter. */
  public McpSnapshotQueries(EntityManager em) {
    this.em = em;
  }

  /** Queries snapshots within the supplied server's verified ownership scope. */
  public Page<AiMcpCapabilitySnapshot> page(
      AiMcpServerDefinition server, Map<String, String> filters, Pageable requested) {
    if (server == null || server.getId() == null || server.getScopeType() == null) {
      throw new IllegalArgumentException("An authorized server is required");
    }
    Pageable page = requested;
    if (requested.isPaged()
        && requested.getSort().isUnsorted()
        && QueryCapabilities.readable(McpCapabilitySnapshotSummary.class, "discoveredAt")) {
      page =
          PageRequest.of(
              requested.getPageNumber(),
              requested.getPageSize(),
              Sort.by(Sort.Direction.DESC, "discoveredAt"));
    }
    var input =
        ProjectionQuery.prepareIgnoringCase(
            McpCapabilitySnapshotSummary.class, filters, page, Set.of("remoteServerName"));
    var cb = em.getCriteriaBuilder();
    var data = cb.createQuery(AiMcpCapabilitySnapshot.class);
    var row = data.from(AiMcpCapabilitySnapshot.class);
    var fields = fields(cb, row, server);
    data.select(row)
        .where(cb.and(boundary(cb, row, server), input.predicate(cb, fields)))
        .orderBy(input.orders(cb, fields, row.get("id")));
    var content =
        em.createQuery(data)
            .setFirstResult((int) page.getOffset())
            .setMaxResults(page.getPageSize())
            .getResultList();
    var count = cb.createQuery(Long.class);
    var counted = count.from(AiMcpCapabilitySnapshot.class);
    count
        .select(cb.count(counted))
        .where(
            cb.and(
                boundary(cb, counted, server), input.predicate(cb, fields(cb, counted, server))));
    return new PageImpl<>(content, page, em.createQuery(count).getSingleResult());
  }

  /** The real chronological predecessor, independent of a UI's current filters, order and page. */
  public java.util.Optional<AiMcpCapabilitySnapshot> previous(
      AiMcpServerDefinition server, String snapshotId) {
    var cb = em.getCriteriaBuilder();
    var selected = cb.createQuery(AiMcpCapabilitySnapshot.class);
    var target = selected.from(AiMcpCapabilitySnapshot.class);
    selected
        .select(target)
        .where(cb.and(boundary(cb, target, server), cb.equal(target.get("id"), snapshotId)));
    var current =
        em.createQuery(selected)
            .getResultStream()
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("MCP capability snapshot does not exist"));
    var data = cb.createQuery(AiMcpCapabilitySnapshot.class);
    var row = data.from(AiMcpCapabilitySnapshot.class);
    data.select(row)
        .where(
            cb.and(
                boundary(cb, row, server),
                cb.or(
                    cb.lessThan(row.get("discoveredAt"), current.getDiscoveredAt()),
                    cb.and(
                        cb.equal(row.get("discoveredAt"), current.getDiscoveredAt()),
                        cb.greaterThan(row.get("id"), current.getId())))))
        .orderBy(cb.desc(row.get("discoveredAt")), cb.asc(row.get("id")));
    return em.createQuery(data).setMaxResults(1).getResultStream().findFirst();
  }

  private Predicate boundary(
      CriteriaBuilder cb, Root<AiMcpCapabilitySnapshot> row, AiMcpServerDefinition server) {
    return cb.and(
        cb.equal(row.get("serverId"), server.getId()),
        cb.equal(row.get("scopeType"), server.getScopeType()),
        server.getTenantId() == null
            ? cb.isNull(row.get("tenantId"))
            : cb.equal(row.get("tenantId"), server.getTenantId()),
        cb.isNull(row.get("deletedAt")));
  }

  private Map<String, Expression<?>> fields(
      CriteriaBuilder cb, Root<AiMcpCapabilitySnapshot> row, AiMcpServerDefinition server) {
    Map<String, Expression<?>> fields = new LinkedHashMap<>();
    for (String field :
        Set.of(
            "discoveredAt",
            "remoteServerName",
            "remoteServerVersion",
            "protocolVersion",
            "schemaHash")) {
      fields.put(field, row.get(field));
    }
    fields.put(
        "active",
        server.getActiveSnapshotId() == null
            ? cb.literal(false)
            : cb.<Boolean>selectCase()
                .when(cb.equal(row.get("id"), server.getActiveSnapshotId()), true)
                .otherwise(false));
    return fields;
  }
}
