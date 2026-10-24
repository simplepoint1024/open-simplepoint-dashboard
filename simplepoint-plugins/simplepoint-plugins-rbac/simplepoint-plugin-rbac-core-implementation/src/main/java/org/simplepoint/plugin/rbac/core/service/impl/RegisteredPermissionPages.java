package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.springframework.security.access.AccessDeniedException;

/** Trusted page projections from exactly one selected resource namespace. */
final class RegisteredPermissionPages {
  private static final int BATCH_SIZE = 100;

  enum Projection {
    EXPRESSION("r.code,r.permissionMetadata"),
    FIELDS("r.code,r.alias,r.name,r.label,r.title,r.permissionMetadata"),
    TREE("r.id,r.parentId,r.code,r.permissionMetadata");
    private final String columns;

    Projection(String columns) {
      this.columns = columns;
    }
  }

  /** Validated namespace and held codes intersected with current tenant eligibility. */
  record Selection(String tenantId, List<String> codes) {
    String source() {
      return tenantId == null ? "Resource" : "TenantResource";
    }
  }

  private RegisteredPermissionPages() {}

  static AuthorizationContext caller() {
    var context = AuthorizationContextHolder.getContext();
    if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
      throw new AccessDeniedException("授权上下文不可用");
    }
    return context;
  }

  static String tenant() {
    var context = caller();
    String tenant = context.getAttribute("X-Tenant-Id");
    if (context.getScopeType() == AuthorizationScopeType.PLATFORM) {
      if (tenant != null && !tenant.isBlank()) {
        throw new AccessDeniedException("工作空间范围不一致");
      }
      return null;
    }
    if ((context.getScopeType() != AuthorizationScopeType.TENANT
            && context.getScopeType() != AuthorizationScopeType.PERSONAL)
        || tenant == null
        || tenant.isBlank()) {
      throw new AccessDeniedException("请选择有效的租户工作空间");
    }
    return tenant;
  }

  static Selection selection(TenantResourceCatalog catalog, String requestedCode) {
    String tenant = tenant();
    Collection<String> held = caller().getResources();
    List<String> codes =
        held == null
            ? List.of()
            : held.stream()
                .filter(Objects::nonNull)
                .filter(code -> !code.isBlank())
                .filter(code -> requestedCode == null || code.equals(requestedCode))
                .distinct()
                .toList();
    if (tenant != null) {
      if (catalog == null) {
        throw new AccessDeniedException("租户资源目录不可用");
      }
      // Query only requested codes and their dependencies; no global registry scan or grant
      // widening.
      if (!codes.isEmpty()) {
        var eligible = catalog.eligiblePermissionCodes(tenant, codes);
        codes = codes.stream().filter(eligible::contains).toList();
      }
    }
    return new Selection(tenant, codes);
  }

  static void forEach(
      EntityManager em,
      TenantResourceCatalog catalog,
      String requestedCode,
      Projection projection,
      Consumer<Object[]> consume) {
    forEachUntil(
        em,
        selection(catalog, requestedCode),
        projection,
        row -> {
          consume.accept(row);
          return true;
        });
  }

  static void forEachUntil(
      EntityManager em,
      TenantResourceCatalog catalog,
      String requestedCode,
      Projection projection,
      Predicate<Object[]> consume) {
    forEachUntil(em, selection(catalog, requestedCode), projection, consume);
  }

  /**
   * Each metadata batch is bounded before parsing; validation can stop before unrelated later
   * batches.
   */
  static void forEachUntil(
      EntityManager em, Selection selection, Projection projection, Predicate<Object[]> consume) {
    String tenant = selection.tenantId();
    String query =
        "select "
            + projection.columns
            + " from "
            + selection.source()
            + " r where "
            + (tenant == null
                ? "r.deletedAt is null and r.disabled=false"
                : "r.tenantId=:tenant and r.enabled=true and r.manualDisabled=false and"
                    + " r.sourceAvailable=true and r.formallyOffline=false and r.targetResourceId"
                    + " is null")
            + " and r.type='PAGE' and r.permissionMetadata is not null and r.code in :codes";
    var codes = selection.codes();
    for (int start = 0; start < codes.size(); start += BATCH_SIZE) {
      var batch = codes.subList(start, Math.min(start + BATCH_SIZE, codes.size()));
      var statement =
          em.createQuery(query, Object[].class)
              .setParameter("codes", batch)
              .setMaxResults(batch.size());
      if (tenant != null) {
        statement.setParameter("tenant", tenant);
      }
      for (var row : statement.getResultList()) {
        if (!consume.test(row)) {
          return;
        }
      }
    }
  }
}
