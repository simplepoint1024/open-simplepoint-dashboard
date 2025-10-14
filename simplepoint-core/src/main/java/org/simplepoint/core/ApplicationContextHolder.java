/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core;

/**
 * A utility class to hold and manage a static reference to {@link ApplicationClassLoader}.
 * Provides methods to set and retrieve the class loader instance.
 */
public class ApplicationContextHolder {

  /**
   * A static reference to the {@link ApplicationClassLoader}.
   * Initialized only once if it is null.
   */
  private static ApplicationClassLoader classloader;

  /**
   * Sets the {@link ApplicationClassLoader} if it has not been set yet
   * and the provided classloader is not null.
   *
   * @param classloader the {@link ApplicationClassLoader} to be set
   */
  public static void setClassloader(ApplicationClassLoader classloader) {
    if (ApplicationContextHolder.classloader == null && classloader != null) {
      ApplicationContextHolder.classloader = classloader;
    }
  }

  /**
   * Retrieves the static reference to the {@link ApplicationClassLoader}.
   *
   * @return the currently set {@link ApplicationClassLoader}, or null if not set
   */
  public static ApplicationClassLoader getClassloader() {
    return ApplicationContextHolder.classloader;
  }
}
