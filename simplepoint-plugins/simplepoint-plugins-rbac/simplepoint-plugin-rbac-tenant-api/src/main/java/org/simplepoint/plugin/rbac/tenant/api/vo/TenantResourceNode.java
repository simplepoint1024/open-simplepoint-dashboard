package org.simplepoint.plugin.rbac.tenant.api.vo;

import org.simplepoint.core.annotation.PermissionResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.security.entity.ResourceNode;

/** Runtime navigation shape with the actual tenant-owned source field policy. */
@PermissionResource(TenantResource.class)
public class TenantResourceNode extends ResourceNode {}
