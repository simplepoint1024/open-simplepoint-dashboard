package org.simplepoint.plugin.rbac.core.api.pojo.command;

public record TenantMemberCommand(String email, String orgId, Boolean enabled, Long revision,
    String userType) {
  public TenantMemberCommand(String email, String orgId, Boolean enabled, Long revision) {
    this(email, orgId, enabled, revision, null);
  }
}
