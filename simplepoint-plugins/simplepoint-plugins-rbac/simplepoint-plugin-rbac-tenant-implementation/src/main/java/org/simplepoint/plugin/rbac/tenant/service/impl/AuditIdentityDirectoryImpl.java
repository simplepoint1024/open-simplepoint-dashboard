package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityLabels;
import org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityRequest;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.identity.UserDisplayName;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-owned scalar labels for references authorized by the consuming business service. */
@Service
@RemoteProvider
public class AuditIdentityDirectoryImpl implements AuditIdentityDirectory {
  private final EntityManager entities;

  /** Binds identity storage without exposing entities or repositories to consumers. */
  public AuditIdentityDirectoryImpl(EntityManager entities) {
    this.entities = entities;
  }

  @Override
  @Transactional(readOnly = true)
  public AuditIdentityLabels resolve(AuditIdentityRequest request) {
    if (ServiceClientIdentity.authenticatedClientId(
            SecurityContextHolder.getContext().getAuthentication())
        .isEmpty()) {
      throw new AccessDeniedException(
          "Audit identity projection requires an authenticated service client");
    }
    if (request == null) {
      throw new IllegalArgumentException("Audit identity request is required");
    }
    // Explicit predicates do not rely on request-scoped Hibernate filters.
    var tenants =
        entities
            .createQuery(
                "select t.name, t.ownerId from Tenant t where t.id = :tenant and t.deletedAt is"
                    + " null",
                Object[].class)
            .setParameter("tenant", request.tenantId())
            .setMaxResults(1)
            .getResultList();
    if (tenants.isEmpty()) {
      return new AuditIdentityLabels(request.tenantId(), null, Map.of(), Map.of(), Map.of());
    }
    Map<String, String> users = new LinkedHashMap<>();
    if (!request.userIds().isEmpty()) {
      entities
          .createQuery(
              "select u.id, "
                  + UserDisplayName.HQL
                  + " from User u where u.id in :ids and u.deletedAt is null and"
                  + " coalesce(u.enabled, true) = true and (u.id = :owner or exists (select m.id"
                  + " from TenantUserRelevance m where m.userId = u.id and m.tenantId = :tenant and"
                  + " m.deletedAt is null and coalesce(m.enabled, true) = true))",
              Object[].class)
          .setParameter("ids", request.userIds())
          .setParameter("tenant", request.tenantId())
          .setParameter("owner", tenants.getFirst()[1])
          .setMaxResults(100)
          .getResultList()
          .forEach(row -> putLabel(users, row));
    }
    Map<String, String> departments = new LinkedHashMap<>();
    if (!request.departmentIds().isEmpty()) {
      entities
          .createQuery(
              "select o.id, o.name from Organization o where o.tenantId = :tenant "
                  + "and o.id in :ids and o.deletedAt is null and o.enabled = true",
              Object[].class)
          .setParameter("tenant", request.tenantId())
          .setParameter("ids", request.departmentIds())
          .setMaxResults(100)
          .getResultList()
          .forEach(row -> putLabel(departments, row));
    }
    Map<String, String> roles = new LinkedHashMap<>();
    if (!request.roleAuthorities().isEmpty()) {
      var rows =
          entities
              .createQuery(
                  "select r.authority, r.roleName from Role r where r.tenantId = :tenant and"
                      + " r.authority in :authorities and r.deletedAt is null order by r.id",
                  Object[].class)
              .setParameter("tenant", request.tenantId())
              .setParameter("authorities", request.roleAuthorities())
              .setMaxResults(501)
              .getResultList();
      if (rows.size() > 500) {
        throw new IllegalStateException("Audit role label projection exceeds its result limit");
      }
      Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
      for (var row : rows) {
        String label = UserDisplayName.text((String) row[1]);
        if (label != null) {
          grouped.computeIfAbsent((String) row[0], ignored -> new LinkedHashSet<>()).add(label);
        }
      }
      grouped.forEach((authority, labels) -> roles.put(authority, String.join(" / ", labels)));
    }
    return new AuditIdentityLabels(
        request.tenantId(),
        request.includeTenant() ? UserDisplayName.text((String) tenants.getFirst()[0]) : null,
        users,
        departments,
        roles);
  }

  private static void putLabel(Map<String, String> labels, Object[] row) {
    String label = UserDisplayName.text((String) row[1]);
    if (label != null) {
      labels.put((String) row[0], label);
    }
  }
}
