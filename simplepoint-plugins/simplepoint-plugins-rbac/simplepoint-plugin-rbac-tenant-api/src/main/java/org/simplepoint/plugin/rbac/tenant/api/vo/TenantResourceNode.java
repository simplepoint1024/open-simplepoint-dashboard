package org.simplepoint.plugin.rbac.tenant.api.vo;

import org.simplepoint.core.annotation.PermissionResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.security.entity.ResourceNode;

/** Runtime navigation shape with the actual tenant-owned source field policy. */
@PermissionResource(TenantResource.class)
public class TenantResourceNode extends ResourceNode {
  @org.simplepoint.core.annotation.PermissionFields(resource = TenantResource.class, value = "name")
  private org.simplepoint.api.locale.LocalizedText nameLocalization;

  public org.simplepoint.api.locale.LocalizedText getNameLocalization() { return nameLocalization; }
  public void setNameLocalization(org.simplepoint.api.locale.LocalizedText value) { nameLocalization = value; }
}
