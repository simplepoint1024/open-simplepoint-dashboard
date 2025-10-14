/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.api.environment;

import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Environment Configuration.
 */
public interface EnvironmentConfiguration {
  String SIMPLEPOINT_DC_ID = "server.center.id";
  String SIMPLEPOINT_WORKER_ID = "server.worker.id";
  String SIMPLEPOINT_PORT = "server.port";
  String SIMPLEPOINT_NAME = "server.name";
  String SIMPLEPOINT_ADDR = "server.address";
  String SIMPLEPOINT_ACTIVE = "server.active";
  String DISCOVERY_ENABLED = "discovery.enabled";
  String DISCOVERY_PORT = "discovery.port";
  String DISCOVERY_IP = "discovery.ip";
  String DISCOVERY_HOST = "discovery.host";

  String HEALTH_PATH = "check.health.path";
  String HEALTH_INTERVAL = "check.health.interval";

  String PRIMITIVE = "primitive.";

  /**
   * apply.
   *
   * @param properties  read config.
   * @param simplepoint write config.
   * @param environment environment.
   */
  void apply(Properties properties, Map<String, Object> simplepoint,
             ConfigurableEnvironment environment);
}
