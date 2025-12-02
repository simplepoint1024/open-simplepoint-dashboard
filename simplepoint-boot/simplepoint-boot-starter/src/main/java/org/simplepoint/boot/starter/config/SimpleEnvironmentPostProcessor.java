/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.boot.starter.config;

import static org.simplepoint.api.environment.EnvironmentConfiguration.PRIMITIVE;
import static org.simplepoint.api.environment.EnvironmentConfiguration.SIMPLEPOINT_ACTIVE;
import static org.simplepoint.api.environment.EnvironmentConfiguration.SIMPLEPOINT_ADDR;
import static org.simplepoint.api.environment.EnvironmentConfiguration.SIMPLEPOINT_NAME;
import static org.simplepoint.api.environment.EnvironmentConfiguration.SIMPLEPOINT_PORT;

import java.util.HashMap;
import java.util.ServiceLoader;
import org.simplepoint.api.environment.EnvironmentConfiguration;
import org.simplepoint.core.properties.CoreProperties;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Environment variable processor.
 * Used to load common environment variables
 */
public class SimpleEnvironmentPostProcessor extends HashMap<String, Object>
    implements EnvironmentPostProcessor {

  private final ServiceLoader<EnvironmentConfiguration> services =
      ServiceLoader.load(EnvironmentConfiguration.class);

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment,
      SpringApplication application
  ) {

    if (CoreProperties.containsKey(SIMPLEPOINT_ADDR)) {
      this.put("server.address", CoreProperties.getProperty(SIMPLEPOINT_ADDR));
    }
    if (CoreProperties.containsKey(SIMPLEPOINT_PORT)) {
      this.put("server.port", CoreProperties.getProperty(SIMPLEPOINT_PORT));
    }
    if (CoreProperties.containsKey(SIMPLEPOINT_NAME)) {
      this.put("spring.application.name", CoreProperties.getProperty(SIMPLEPOINT_NAME));
    }
    if (CoreProperties.containsKey(SIMPLEPOINT_ACTIVE)) {
      environment.setActiveProfiles(CoreProperties.getProperty(SIMPLEPOINT_ACTIVE));
    }
    for (EnvironmentConfiguration service : this.services) {
      service.apply(CoreProperties.getProperties(), this, environment);
    }
    CoreProperties.getProperties().keySet().forEach(key -> {
      String name = String.valueOf(key);
      if (name != null && name.startsWith(PRIMITIVE)) {
        this.put(name.substring(PRIMITIVE.length()), CoreProperties.get(name));
      }
    });
    environment.getPropertySources().addFirst(new MapPropertySource("simplepointProperties", this));
  }
}
