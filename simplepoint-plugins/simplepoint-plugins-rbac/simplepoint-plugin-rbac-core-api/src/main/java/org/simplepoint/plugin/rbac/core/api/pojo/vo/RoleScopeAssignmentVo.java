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
 * <p>New assignments are independent of resource grants. Revision protects concurrent edits;
 * legacy conflicts require explicit confirmation before replacing the old policy union.</p>
 */
public class RoleScopeAssignmentVo {

  private Long revision;
  private boolean legacyConflict;
  private boolean confirmLegacyReplacement;
  private java.util.List<String> legacyDataScopeIds = java.util.List.of();
  private java.util.List<String> legacyFieldScopeIds = java.util.List.of();

  public Long getRevision() { return revision; }
  public void setRevision(Long revision) { this.revision = revision; }
  public boolean isLegacyConflict() { return legacyConflict; }
  public void setLegacyConflict(boolean value) { this.legacyConflict = value; }
  public boolean isConfirmLegacyReplacement() { return confirmLegacyReplacement; }
  public void setConfirmLegacyReplacement(boolean value) { this.confirmLegacyReplacement = value; }
  public java.util.List<String> getLegacyDataScopeIds() { return legacyDataScopeIds; }
  public void setLegacyDataScopeIds(java.util.List<String> value) { this.legacyDataScopeIds = value; }
  public java.util.List<String> getLegacyFieldScopeIds() { return legacyFieldScopeIds; }
  public void setLegacyFieldScopeIds(java.util.List<String> value) { this.legacyFieldScopeIds = value; }

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
