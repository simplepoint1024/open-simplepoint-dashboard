/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package  org.simplepoint.cloud.consul;

import java.util.Map;
import java.util.Properties;
import org.simplepoint.api.environment.EnvironmentConfiguration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Handle environment variables related to Consul configuration.
 */
public class ConsulEnvironmentConfiguration implements EnvironmentConfiguration {
  @Override
  public void apply(Properties properties, Map<String, Object> simplepoint,
                    ConfigurableEnvironment environment) {
    if (properties.containsKey(SIMPLEPOINT_DC_ID)) {
      simplepoint.put("spring.cloud.consul.discovery.instance-id",
          "${spring.application.name}-" + properties.getProperty(SIMPLEPOINT_DC_ID));
    }
    if (properties.containsKey(DISCOVERY_ENABLED)) {
      simplepoint.put("spring.cloud.discovery.enabled", properties.getProperty(DISCOVERY_ENABLED));
    }
    if (properties.containsKey(DISCOVERY_IP)) {
      simplepoint.put("spring.cloud.consul.discovery.prefer-ip-address", "true");
      simplepoint.put("spring.cloud.consul.discovery.ip-address",
          properties.getProperty(DISCOVERY_IP));
    }
    if (properties.containsKey(DISCOVERY_HOST)) {
      simplepoint.put("spring.cloud.consul.host", properties.getProperty(DISCOVERY_HOST));
      simplepoint.put("spring.cloud.consul.discovery.port.host", properties.getProperty(DISCOVERY_HOST));
    }
    if (properties.containsKey(DISCOVERY_PORT)) {
      simplepoint.put("spring.cloud.consul.port", properties.getProperty(DISCOVERY_PORT));
    }
    if (properties.containsKey(HEALTH_PATH)) {
      simplepoint.put("spring.cloud.consul.discovery.health-check-path",
          properties.getProperty(HEALTH_PATH));
    }
    if (properties.containsKey(HEALTH_INTERVAL)) {
      simplepoint.put("spring.cloud.consul.discovery.health-interval",
          properties.getProperty(HEALTH_INTERVAL));
    }
  }
}
