package org.simplepoint.plugin.rbac.tenant.api.vo;

import java.util.Set;

/** Bounded internal request. Callers must omit hidden and masked audit references. */
public record AuditIdentityRequest(
    String tenantId,
    boolean includeTenant,
    Set<String> userIds,
    Set<String> departmentIds,
    Set<String> roleAuthorities) {
  /** Preserves source compatibility for callers that only resolve record audit references. */
  public AuditIdentityRequest(
      String tenantId, boolean includeTenant, Set<String> userIds, Set<String> departmentIds) {
    this(tenantId, includeTenant, userIds, departmentIds, Set.of());
  }

  /** Validates without normalizing opaque identities into a different reference. */
  public AuditIdentityRequest {
    if (!valid(tenantId, 64)) {
      throw new IllegalArgumentException("A tenant identity is required");
    }
    userIds = copy(userIds, 64);
    departmentIds = copy(departmentIds, 64);
    roleAuthorities = copy(roleAuthorities, 100);
    if (userIds.size() + departmentIds.size() + roleAuthorities.size() > 100) {
      throw new IllegalArgumentException("Audit identity batches are limited to 100 references");
    }
  }

  private static Set<String> copy(Set<String> values, int length) {
    if (values == null) {
      return Set.of();
    }
    if (values.size() > 100 || values.stream().anyMatch(value -> !valid(value, length))) {
      throw new IllegalArgumentException("Invalid audit identity batch");
    }
    return Set.copyOf(values);
  }

  private static boolean valid(String value, int length) {
    return value != null
        && !value.isBlank()
        && value.codePointCount(0, value.length()) <= length
        && value.equals(value.strip())
        && !"***".equals(value);
  }
}
