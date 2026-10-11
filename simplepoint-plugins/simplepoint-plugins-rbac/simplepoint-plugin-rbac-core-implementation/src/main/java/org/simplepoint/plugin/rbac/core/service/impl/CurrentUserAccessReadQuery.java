package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.*;
import org.simplepoint.security.entity.ResourceType;

/** Structural scalar lookups only; visible metadata is still resolved through its actual catalog boundary. */
final class CurrentUserAccessReadQuery {
  private final EntityManager em;
  CurrentUserAccessReadQuery(EntityManager em) {this.em = em;}
  record Edge(String id, String parent, String code, ResourceType type) {}
  record OrganizationNode(String id, String parent, String name) {}

  /** At most the remaining budget plus one overflow witness, across all parent batches. */
  List<Edge> children(Collection<String> parents, String tenant, int remainingBudget) {return edges("parentId", parents, tenant, true, remainingBudget);}
  List<Edge> parents(Collection<String> ids) {return edges("id", ids, null, false, Integer.MAX_VALUE);}

  String assignedOrganization(String tenant, String user) {
    var rows = em.createQuery("select m.orgId from TenantUserRelevance m where m.tenantId = :tenant"
        + " and m.userId = :user and m.deletedAt is null and m.enabled = true", String.class)
        .setParameter("tenant", tenant).setParameter("user", user).setMaxResults(1).getResultList();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  OrganizationNode organization(String tenant, String id) {
    var rows = em.createQuery("select o.id as id, o.parentId as parent, o.name as name from Organization o"
        + " where o.id = :id and o.tenantId = :tenant and o.deletedAt is null and o.enabled = true", Tuple.class)
        .setParameter("id", id).setParameter("tenant", tenant).setMaxResults(1).getResultList();
    if (rows.isEmpty()) return null;
    var row = rows.getFirst(); return new OrganizationNode(row.get("id", String.class), row.get("parent", String.class), row.get("name", String.class));
  }

  private List<Edge> edges(String property, Collection<String> requested, String tenant, boolean children, int budget) {
    if (budget < 0) throw new IllegalArgumentException("Remaining detail budget cannot be negative");
    var values = new ArrayList<>(new LinkedHashSet<>(requested)); var result = new ArrayList<Edge>();
    for (int start = 0; start < values.size(); start += 100) {
      String after = null;
      while (true) {
        int batchSize = (int) Math.min(100L, 1L + budget - result.size());
        String entity = tenant == null ? "Resource" : "TenantResource";
        String boundary = tenant == null ? "r.deletedAt is null and r.disabled = false" : "r.tenantId = :tenant and r.enabled = true";
        var query = em.createQuery("select r.id as id, r.parentId as parent, r.code as code, r.type as type from " + entity + " r where " + boundary
            + " and r." + property + " in :values" + (after == null ? "" : " and r.id > :after") + " order by r.id", Tuple.class)
            .setParameter("values", values.subList(start, Math.min(start + 100, values.size()))).setMaxResults(batchSize);
        if (tenant != null) query.setParameter("tenant", tenant);
        if (after != null) query.setParameter("after", after);
        var rows = query.getResultList();
        for (var row : rows) result.add(new Edge(row.get("id", String.class), row.get("parent", String.class), row.get("code", String.class), row.get("type", ResourceType.class)));
        if (result.size() > budget) return List.copyOf(result);
        if (rows.size() < batchSize || !children) break; after = rows.getLast().get("id", String.class);
      }
    }
    return result;
  }
}
