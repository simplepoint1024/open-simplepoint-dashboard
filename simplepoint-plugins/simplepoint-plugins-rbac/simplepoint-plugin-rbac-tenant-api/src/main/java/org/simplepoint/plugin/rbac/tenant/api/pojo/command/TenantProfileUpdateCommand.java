package org.simplepoint.plugin.rbac.tenant.api.pojo.command;

import lombok.Data;

/** Editable branding and descriptive fields for the active tenant. */
@Data
public class TenantProfileUpdateCommand {

  private String name;

  private String description;

  private String logo;

  private String backgroundImage;
}
