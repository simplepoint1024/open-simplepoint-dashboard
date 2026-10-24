package org.simplepoint.plugin.rbac.tenant.api.model;

import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TablePagination;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.security.entity.ResourceType;

/** Scalar list-query contract, separate from resource mutations and navigation serialization. */
@TableQuery(
    value = {
      @QueryField("name"),
      @QueryField("code"),
      @QueryField("type"),
      @QueryField("origin"),
      @QueryField("enabled"),
      @QueryField("sort"),
      @QueryField("createdAt"),
      @QueryField("updatedAt"),
      @QueryField("lastSyncedAt")
    },
    maxPageSize = TablePagination.MAX_PAGE_SIZE)
public record TenantResourceQuery(
    @PermissionFields(resource = TenantResource.class, value = "name") String name,
    @PermissionFields(resource = TenantResource.class, value = "code") String code,
    @PermissionFields(resource = TenantResource.class, value = "type") ResourceType type,
    @PermissionFields(resource = TenantResource.class, value = {"platformResourceId", "managedOwner"}) String origin,
    @PermissionFields(resource = TenantResource.class, value = "enabled") boolean enabled,
    @PermissionFields(resource = TenantResource.class, value = "sort") Integer sort,
    @PermissionFields(resource = TenantResource.class, value = "createdAt") java.time.Instant createdAt,
    @PermissionFields(resource = TenantResource.class, value = "updatedAt") java.time.Instant updatedAt,
    @PermissionFields(resource = TenantResource.class, value = "lastSyncedAt") java.time.Instant lastSyncedAt) {}
