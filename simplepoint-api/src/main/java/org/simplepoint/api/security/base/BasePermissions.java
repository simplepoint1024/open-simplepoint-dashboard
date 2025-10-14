/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.api.security.base;

/**
 * Permission entity.
 */
public interface BasePermissions {

  /**
   * Permission identifier.
   *
   * @return Permission identifier
   */
  String getAuthority();

  /**
   * Permission identifier.
   *
   * @param authority Permission identifier
   */
  void setAuthority(String authority);

  /**
   * Resource type(menu,field,button).
   *
   * @return Resource type.
   */
  String getResourceType();

  /**
   * Resource type.
   *
   * @param resourceType Resource type.
   */
  void setResourceType(String resourceType);

  /**
   * Resource.
   *
   * @return Resource.
   */
  String getResource();

  /**
   * Resource.
   *
   * @param resource Resource.
   */
  void setResource(String resource);
}
