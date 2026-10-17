package org.simplepoint.plugin.rbac.tenant.api.model;

import java.time.Instant;
import org.simplepoint.security.entity.ResourceType;

/** Read model returned by the managed tenant resource contract. */
public record ManagedTenantResourceView(
    String id,
    String tenantId,
    String owner,
    String managedKey,
    String resourceCode,
    String name,
    ResourceType type,
    String parentId,
    String path,
    String component,
    String routeKind,
    boolean enabled,
    boolean grantable,
    Instant updatedAt
) {}
