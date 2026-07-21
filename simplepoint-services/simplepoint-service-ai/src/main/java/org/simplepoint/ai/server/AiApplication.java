/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.ai.server;

import com.simplepoint.service.router.annotation.EnableServiceRouter;
import org.simplepoint.boot.starter.Boot;
import org.simplepoint.data.jpa.base.repository.EnableRepository;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Main entry point for the SimplePoint AI service.
 */
@Boot
@EnableCaching
@EnableRepository
@EnableMethodSecurity
@EnableServiceRouter(basePackages = "org.simplepoint")
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class AiApplication {

  /**
   * Starts the AI service.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    org.simplepoint.boot.starter.Application.run(AiApplication.class, args);
  }
}
