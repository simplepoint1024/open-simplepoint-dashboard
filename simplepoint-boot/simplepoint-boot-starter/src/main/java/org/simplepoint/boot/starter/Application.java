/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.boot.starter;

import java.net.URL;
import org.simplepoint.core.ApplicationClassLoader;
import org.simplepoint.core.ApplicationContextHolder;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Main startup program.
 */
public class Application {
  /**
   * main.
   *
   * @param primarySource primarySource.
   * @param args          args.
   * @return spring application contest.
   */
  public static ConfigurableApplicationContext run(Class<?> primarySource, String... args) {
    ApplicationContextHolder.setClassloader(
        new ApplicationClassLoader(new URL[] {}, Thread.currentThread().getContextClassLoader()));
    Thread.currentThread().setContextClassLoader(ApplicationContextHolder.getClassloader());
    return SpringApplication.run(primarySource, args);
  }
}
