package org.simplepoint.plugin.rbac.tenant.api.vo;

import java.util.Map;

/** Name-only response; absent entries mean unavailable, removed or outside this tenant. */
public record AuditIdentityLabels(
    String tenantId,
    String tenantName,
    Map<String, String> users,
    Map<String, String> departments,
    Map<String, String> roles) {
  /** Preserves source compatibility for existing audit consumers. */
  public AuditIdentityLabels(
      String tenantId,
      String tenantName,
      Map<String, String> users,
      Map<String, String> departments) {
    this(tenantId, tenantName, users, departments, Map.of());
  }

  /** Copies display projections; credentials and authorization state never enter this DTO. */
  public AuditIdentityLabels {
    users = users == null ? Map.of() : Map.copyOf(users);
    departments = departments == null ? Map.of() : Map.copyOf(departments);
    roles = roles == null ? Map.of() : Map.copyOf(roles);
  }
}
