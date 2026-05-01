/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.datascopeannotation;

import java.util.Set;

/**
 * Immutable value object carrying the resolved data scope condition for the current request.
 * This is stored in {@link DataScopeContext} and read by the repository layer to build query predicates.
 */
public final class DataScopeCondition {

  private final String scopeType;
  private final String deptField;
  private final String ownerField;
  private final String userId;
  private final Set<String> deptIds;

  /**
   * Constructs a DataScopeCondition.
   *
   * @param scopeType  the data scope type name (corresponds to DataScopeType enum name)
   * @param deptField  the department field name on the entity
   * @param ownerField the owner field name on the entity
   * @param userId     the current user's ID
   * @param deptIds    the effective department IDs
   */
  public DataScopeCondition(String scopeType, String deptField, String ownerField, String userId, Set<String> deptIds) {
    this.scopeType = scopeType;
    this.deptField = deptField;
    this.ownerField = ownerField;
    this.userId = userId;
    this.deptIds = deptIds;
  }

  public String getScopeType() {
    return scopeType;
  }

  public String getDeptField() {
    return deptField;
  }

  public String getOwnerField() {
    return ownerField;
  }

  public String getUserId() {
    return userId;
  }

  public Set<String> getDeptIds() {
    return deptIds;
  }
}
