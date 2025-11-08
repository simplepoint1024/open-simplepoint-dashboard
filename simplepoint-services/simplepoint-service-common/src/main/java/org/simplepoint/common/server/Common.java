/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.common.server;

import org.simplepoint.boot.starter.Boot;
import org.simplepoint.data.jpa.base.repository.EnableRepository;
import org.springframework.cache.annotation.EnableCaching;

/**
 * The main entry point for the Simplepoint application.
 * This class is annotated with @Boot and @EnableRepository to configure
 * the application and enable repository-related features, including JPA repositories.
 */
@Boot
@EnableCaching
@EnableRepository
//@EnableMethodSecurity
public class Common {

  /**
   * The main method that starts the Simplepoint application.
   * This method delegates to the Application.run() method to bootstrap the application.
   *
   * @param args the command-line arguments passed during application startup
   */
  public static void main(String[] args) {
    org.simplepoint.boot.starter.Application.run(Common.class, args);
  }
}
