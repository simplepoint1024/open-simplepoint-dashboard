package org.simplepoint.plugin.rbac.tenant.api.model;

import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.core.query.QueryField;
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
      @QueryField("sort")
    },
    maxPageSize = 100)
public record TenantResourceQuery(
    @PermissionFields(resource = TenantResource.class, value = "name") String name,
    @PermissionFields(resource = TenantResource.class, value = "code") String code,
    @PermissionFields(resource = TenantResource.class, value = "type") ResourceType type,
    @PermissionFields(resource = TenantResource.class, value = "platformResourceId") String origin,
    @PermissionFields(resource = TenantResource.class, value = "enabled") boolean enabled,
    @PermissionFields(resource = TenantResource.class, value = "sort") Integer sort) {}
