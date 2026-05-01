/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.pojo.vo;

/**
 * Value object representing the data scope and field scope assignment for a role.
 *
 * <p>A role's permissions can be associated with a {@code DataScope} (row-level filtering)
 * and a {@code FieldScope} (field-level access control). This VO is used to read or update
 * the current scope assignment for all permission records belonging to a role.</p>
 */
public class RoleScopeAssignmentVo {

  private String roleId;

  private String dataScopeId;

  private String fieldScopeId;

  public String getRoleId() {
    return roleId;
  }

  public void setRoleId(String roleId) {
    this.roleId = roleId;
  }

  public String getDataScopeId() {
    return dataScopeId;
  }

  public void setDataScopeId(String dataScopeId) {
    this.dataScopeId = dataScopeId;
  }

  public String getFieldScopeId() {
    return fieldScopeId;
  }

  public void setFieldScopeId(String fieldScopeId) {
    this.fieldScopeId = fieldScopeId;
  }
}
