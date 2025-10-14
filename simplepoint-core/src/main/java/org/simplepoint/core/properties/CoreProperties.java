/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.properties;

import static org.simplepoint.api.environment.EnvironmentConfiguration.SIMPLEPOINT_DC_ID;
import static org.simplepoint.api.environment.EnvironmentConfiguration.SIMPLEPOINT_WORKER_ID;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * CoreProperties.
 */
@Slf4j
public class CoreProperties {
  private static final Logger logger = LoggerFactory.getLogger(CoreProperties.class);
  private static final Properties config = new Properties();
  public static long workerId;
  public static long dcId;
  private static final Map<Integer, AbstractResource> resources = new TreeMap<>();

  static {
    addResource(0, new FileSystemResource("conf/simple-public.properties"));
    addResource(1, new ClassPathResource("conf/simple.properties"));
    load();
    if (!containsKey(SIMPLEPOINT_WORKER_ID)) {
      throw new NullPointerException("config " + SIMPLEPOINT_WORKER_ID + " is not found");
    }
    workerId = Long.parseLong(config.getProperty(SIMPLEPOINT_WORKER_ID));
    if (!containsKey(SIMPLEPOINT_DC_ID)) {
      throw new NullPointerException("config " + SIMPLEPOINT_DC_ID + " is not found");
    }
    dcId = Long.parseLong(config.getProperty(SIMPLEPOINT_DC_ID));
  }

  /**
   * loader properties.
   */
  public static void load() {
    resources.values().forEach(pop -> {
      try {
        if (pop.exists() && pop.isFile()) {
          logger.info("loading properties from {}", pop.getFilename());
          config.putAll(PropertiesLoaderUtils.loadProperties(pop));
        } else {
          logger.info("invalid properties file {}", pop.getFilename());
        }
      } catch (IOException e) {
        logger.warn("Failed to load properties file : {}", e.getMessage());
      }
    });
  }

  /**
   * add classpath resource.
   *
   * @param order    order
   * @param resource resource
   */
  public static void addResource(int order, AbstractResource resource) {
    if (resources.containsKey(order)) {
      throw new IllegalArgumentException("order " + order + " already exists");
    }
    resources.put(order, resource);
  }

  /**
   * get property.
   *
   * @param key key
   * @return value
   */
  public static Object get(String key) {
    return config.get(key);
  }

  /**
   * getProperty.
   *
   * @param key key
   * @return value
   */
  public static String getProperty(String key) {
    return config.getProperty(key);
  }

  /**
   * containsKey.
   *
   * @param key key
   * @return exists
   */
  public static boolean containsKey(String key) {
    return config.containsKey(key);
  }

  /**
   * getProperties.
   *
   * @return properties
   */
  public static Properties getProperties() {
    return config;
  }
}
