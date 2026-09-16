package org.simplepoint.plugin.rbac.core.api.pojo.vo;

public record TenantMemberView(
    String userId,
    String name,
    String email,
    String orgId,
    String orgName,
    boolean enabled,
    Long revision
) { }
