/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for plugin runtime task storage.
 */
@ConfigurationProperties(prefix = "plugin.task-store")
public class PluginTaskStoreProperties {

  private final Jdbc jdbc = new Jdbc();

  /**
   * Returns JDBC task store configuration.
   *
   * @return JDBC task store configuration
   */
  public Jdbc getJdbc() {
    return jdbc;
  }

  /**
   * JDBC task store configuration.
   */
  public static class Jdbc {

    private boolean enabled;
    private boolean initializeSchema = true;
    private String tableName = "sp_plugin_task";

    /**
     * Whether the JDBC task store is enabled.
     *
     * @return whether the JDBC task store is enabled
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Sets whether the JDBC task store is enabled.
     *
     * @param enabled whether the JDBC task store is enabled
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * Whether to initialize the task table when the store is created.
     *
     * @return whether schema initialization is enabled
     */
    public boolean isInitializeSchema() {
      return initializeSchema;
    }

    /**
     * Sets whether to initialize the task table when the store is created.
     *
     * @param initializeSchema whether schema initialization is enabled
     */
    public void setInitializeSchema(boolean initializeSchema) {
      this.initializeSchema = initializeSchema;
    }

    /**
     * Returns the task table name.
     *
     * @return task table name
     */
    public String getTableName() {
      return tableName;
    }

    /**
     * Sets the task table name.
     *
     * @param tableName task table name
     */
    public void setTableName(String tableName) {
      this.tableName = tableName;
    }
  }
}
