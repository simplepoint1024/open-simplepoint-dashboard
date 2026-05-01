/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.entity;

/**
 * Defines the data scope type for row-level access control.
 * Types are ordered from most permissive (highest value) to most restrictive (lowest value).
 */
public enum DataScopeType {

  /**
   * Restricted to data created by the current user only.
   */
  SELF(0),

  /**
   * Custom set of organizational units (departments) specified explicitly.
   */
  CUSTOM(1),

  /**
   * Restricted to the current user's own department.
   */
  DEPT(2),

  /**
   * Restricted to the current user's department and all its sub-departments.
   */
  DEPT_AND_BELOW(3),

  /**
   * No restriction — all data is accessible.
   */
  ALL(4);

  private final int permissiveLevel;

  DataScopeType(int permissiveLevel) {
    this.permissiveLevel = permissiveLevel;
  }

  /**
   * Returns the permissive level of this scope type.
   * Higher values indicate broader (less restrictive) access.
   *
   * @return the permissive level
   */
  public int getPermissiveLevel() {
    return permissiveLevel;
  }
}
