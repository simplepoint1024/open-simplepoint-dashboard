package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;

/** Trusted registered-page projections. Authorization, parameters and each SQL result are bounded before JSON parsing. */
final class RegisteredPermissionPages {
  private static final int BATCH_SIZE = 100;
  enum Projection {
    EXPRESSION("r.code,r.permissionMetadata"),
    FIELDS("r.code,r.alias,r.name,r.label,r.title,r.permissionMetadata");
    private final String columns;
    Projection(String columns) {this.columns = columns;}
  }
  private RegisteredPermissionPages() {}
  static AuthorizationContext caller() {
    var context = AuthorizationContextHolder.getContext();
    if (context == null || context.getUserId() == null || context.getUserId().isBlank()) throw new org.springframework.security.access.AccessDeniedException("授权上下文不可用");
    return context;
  }
  static void forEach(EntityManager em, String requestedCode, Projection projection, Consumer<Object[]> consume) {
    forEachUntil(em, requestedCode, projection, row -> {consume.accept(row); return true;});
  }
  /** Stop parsing/fetching once a requested validation is complete; presentation callers still consume the complete union. */
  static void forEachUntil(EntityManager em, String requestedCode, Projection projection, Predicate<Object[]> consume) {
    var codes = caller().getResources();
    if (codes == null || codes.isEmpty() || requestedCode != null && (requestedCode.isBlank() || !codes.contains(requestedCode))) return;
    String query = "select " + projection.columns + " from Resource r where r.deletedAt is null"
        + " and r.disabled=false and r.type='PAGE' and r.permissionMetadata is not null";
    if (requestedCode != null) {
      for (var row : em.createQuery(query + " and r.code=:code", Object[].class).setParameter("code", requestedCode)
          .setMaxResults(1).getResultList()) if (!consume.test(row)) return;
      return;
    }
    var requested = codes.stream().filter(Objects::nonNull).filter(code -> !code.isBlank()).distinct().toList();
    // Resource.code has a database unique constraint: this cap bounds one batch, not the complete union.
    for (int start = 0; start < requested.size(); start += BATCH_SIZE) {
      var batch = requested.subList(start, Math.min(start + BATCH_SIZE, requested.size()));
      for (var row : em.createQuery(query + " and r.code in :codes", Object[].class).setParameter("codes", batch)
          .setMaxResults(batch.size()).getResultList()) if (!consume.test(row)) return;
    }
  }
}
