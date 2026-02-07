/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.api.security.base;

import org.springframework.security.core.userdetails.UserDetails;

/**
 * base user.
 */
public interface BaseUser extends UserDetails {
  /**
   * Check if the user has admin privileges.
   *
   * @return true if the user is an admin, false otherwise.
   */
  Boolean superAdmin();
}
