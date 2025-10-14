/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.api.security.base;

/**
 * Base Role.
 */
public interface BaseRole {

  /**
   * RoleName.
   *
   * @return RoleName.
   */
  String getRoleName();

  /**
   * roleName.
   *
   * @param roleName roleName
   */
  void setRoleName(String roleName);

  /**
   * Permission identifier.
   *
   * @return Permission identifier
   */
  String getAuthority();

  /**
   * Permission identifier.
   *
   * @param roleScope Permission identifier
   */
  void setAuthority(String roleScope);
}
