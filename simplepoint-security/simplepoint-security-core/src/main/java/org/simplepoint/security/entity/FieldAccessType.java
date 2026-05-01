/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.entity;

/**
 * Defines the field-level access type for column-level access control.
 * Types are ordered from most permissive (highest value) to most restrictive (lowest value).
 */
public enum FieldAccessType {

  /**
   * Field is completely hidden from the response.
   */
  HIDDEN(0),

  /**
   * Field value is masked (e.g. partially obfuscated) in the response.
   */
  MASKED(1),

  /**
   * Field is visible but read-only.
   */
  VISIBLE(2),

  /**
   * Field is visible and editable.
   */
  EDITABLE(3);

  private final int permissiveLevel;

  FieldAccessType(int permissiveLevel) {
    this.permissiveLevel = permissiveLevel;
  }

  /**
   * Returns the permissive level of this access type.
   * Higher values indicate broader (less restrictive) access.
   *
   * @return the permissive level
   */
  public int getPermissiveLevel() {
    return permissiveLevel;
  }
}
