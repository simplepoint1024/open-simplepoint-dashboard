package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;

/** One active, tenant-bound assignment definition for overviews, details and paged impact rows. */
final class RoleUserMembership {
  private RoleUserMembership() {}

  static Predicate active(
      CriteriaBuilder cb,
      AbstractQuery<?> query,
      Root<User> user,
      String tenantId,
      Expression<String> roleId) {
    var exists = query.subquery(Integer.class);
    var membership = exists.from(UserRoleRelevance.class);
    exists
        .select(cb.literal(1))
        .where(
            cb.equal(membership.get("tenantId"), tenantId),
            cb.equal(membership.get("roleId"), roleId),
            cb.equal(membership.get("userId"), user.get("id")),
            cb.isNull(membership.get("deletedAt")));
    return cb.and(cb.isNull(user.get("deletedAt")), cb.exists(exists));
  }
}
