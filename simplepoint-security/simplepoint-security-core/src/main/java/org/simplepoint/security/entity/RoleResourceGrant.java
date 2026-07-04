/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;

/**
 * Role-to-resource grant with optional data and field constraints.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@Table(name = "simpoint_ac_role_resource_grants")
@Schema(title = "角色资源授权实体", description = "表示角色与资源之间的授权关系")
public class RoleResourceGrant extends TenantBaseEntityImpl<String> {

  @Column(nullable = false, length = 36)
  @Schema(title = "角色标识", description = "被授权的角色 ID")
  private String roleId;

  @Column(nullable = false, length = 120)
  @Schema(title = "资源编码", description = "被授权的资源编码")
  private String resourceCode;

  @Schema(title = "数据权限标识", description = "资源授权附带的数据范围")
  private String dataScopeId;

  @Schema(title = "字段权限标识", description = "资源授权附带的字段范围")
  private String fieldScopeId;
}
