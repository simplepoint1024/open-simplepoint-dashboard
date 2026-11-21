package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.simplepoint.core.query.TablePagination;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageResourceView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Bounded package-detail projection. Never joins the live platform resource catalog. */
final class TenantPackageResourceQuery {
  private static final Map<String, String> SORTS =
      Map.of(
          "id",
          "key(d)",
          "resourceCode",
          "coalesce(d.resourceCode, key(d))",
          "resourceName",
          "coalesce(d.resourceName, key(d))");
  private final EntityManager em;

  TenantPackageResourceQuery(EntityManager em) {
    this.em = em;
  }

  Page<TenantPackageResourceView> query(
      String tenantId, String instanceId, Pageable pageable, Instant now) {
    if (instanceId == null || instanceId.isBlank() || instanceId.length() > 36) {
      throw new TenantPackageException(Code.INSTANCE_NOT_FOUND);
    }
    if (pageable == null
        || pageable.isUnpaged()
        || pageable.getPageSize() > TablePagination.MAX_PAGE_SIZE
        || pageable.getOffset() > Integer.MAX_VALUE
        || pageable.getSort().stream().count() > 3) {
      throw new TenantPackageException(Code.INVALID_REQUEST);
    }
    var ordering = new ArrayList<String>();
    var seen = new java.util.HashSet<String>();
    for (var order : pageable.getSort()) {
      String expression = SORTS.get(order.getProperty());
      if (expression == null || !seen.add(order.getProperty())) {
        throw new TenantPackageException(Code.INVALID_REQUEST);
      }
      ordering.add(expression + (order.isAscending() ? " asc" : " desc"));
    }
    if (!seen.contains("id")) {
      ordering.add("key(d) asc");
    }
    if (em.createQuery(
                "select count(i) from TenantPackageInstance i where i.tenantId = :tenant and i.id ="
                    + " :id",
                Long.class)
            .setParameter("tenant", tenantId)
            .setParameter("id", instanceId)
            .getSingleResult()
        == 0L) {
      throw new TenantPackageException(Code.INSTANCE_NOT_FOUND);
    }
    var rows =
        em.createQuery(
                "select key(d) as id, coalesce(d.resourceCode, key(d)) as code,"
                    + " coalesce(d.resourceName, key(d)) as name, i.durationDays as days,"
                    + " d.supplemental as supplemental,"
                    + " i.acquisition as acquisition, c.activatedAt as activated, c.resultExpiresAt"
                    + " as credited, i.revokedAt as revoked, e.startsAt as starts, e.expiresAt as"
                    + " expires from TenantPackageInstance i join i.resources d left join"
                    + " TenantResourceCredit c on c.tenantId = i.tenantId and c.instanceId = i.id"
                    + " and c.resourceId = key(d) left join TenantResourceEntitlement e on"
                    + " e.tenantId = i.tenantId and e.id = key(d) where i.tenantId = :tenant and"
                    + " i.id = :id order by "
                    + String.join(", ", ordering),
                Tuple.class)
            .setParameter("tenant", tenantId)
            .setParameter("id", instanceId)
            .setFirstResult((int) pageable.getOffset())
            .setMaxResults(pageable.getPageSize())
            .getResultList();
    var content =
        rows.stream()
            .map(
                row -> {
                  Instant starts = row.get("starts", Instant.class);
                  Instant expires = row.get("expires", Instant.class);
                  boolean supplemental = row.get("supplemental", Boolean.class);
                  return new TenantPackageResourceView(
                      row.get("id", String.class),
                      row.get("code", String.class),
                      row.get("name", String.class),
                      row.get("days", Integer.class),
                      supplemental || row.get(
                                  "acquisition",
                                  org.simplepoint.plugin.rbac.tenant.api.entity
                                      .TenantPackageInstance.Acquisition.class)
                              == org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance
                                  .Acquisition.ASSIGNED
                          ? row.get("activated", Instant.class)
                          : null,
                      row.get("credited", Instant.class),
                      row.get("revoked", Instant.class),
                      starts,
                      expires,
                      TenantPackageResourceView.stateAt(starts, expires, now),
                      supplemental);
                })
            .toList();
    long total =
        em.createQuery(
                "select count(key(d)) from TenantPackageInstance i join i.resources d "
                    + "where i.tenantId = :tenant and i.id = :id",
                Long.class)
            .setParameter("tenant", tenantId)
            .setParameter("id", instanceId)
            .getSingleResult();
    return new PageImpl<>(content, pageable, total);
  }
}
