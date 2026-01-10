/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.api.security.base;

import java.io.Serializable;

/**
 * base user relevance.
 */
public interface BaseUserRoleRelevance extends Serializable {
  /**
   * userId.
   *
   * @return userId.
   */
  String getUserId();

  /**
   * userId.
   *
   * @param userId userId.
   */
  void setUserId(String userId);

  /**
   * roleId.
   *
   * @return roleId.
   */
  String getRoleId();

  /**
   * roleId.
   *
   * @param roleId roleId
   */
  void setRoleId(String roleId);
}
