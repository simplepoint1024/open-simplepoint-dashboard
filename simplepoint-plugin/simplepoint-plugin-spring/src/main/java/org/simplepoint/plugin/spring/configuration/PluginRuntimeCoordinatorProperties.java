/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for plugin runtime coordination.
 */
@ConfigurationProperties(prefix = "plugin.runtime-coordinator")
public class PluginRuntimeCoordinatorProperties {

  private final Jdbc jdbc = new Jdbc();

  /**
   * Returns JDBC runtime coordinator configuration.
   *
   * @return JDBC runtime coordinator configuration
   */
  public Jdbc getJdbc() {
    return jdbc;
  }

  /**
   * JDBC runtime coordinator configuration.
   */
  public static class Jdbc {

    private boolean enabled;
    private boolean initializeSchema = true;
    private String tableName = "sp_plugin_runtime_lock";
    private String lockName = "plugin-runtime";
    private String ownerId;
    private Duration leaseDuration = Duration.ofMinutes(5);
    private Duration acquireTimeout = Duration.ofSeconds(30);
    private Duration retryInterval = Duration.ofMillis(200);

    /**
     * Whether JDBC runtime coordination is enabled.
     *
     * @return whether JDBC runtime coordination is enabled
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Sets whether JDBC runtime coordination is enabled.
     *
     * @param enabled whether JDBC runtime coordination is enabled
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * Whether to initialize the lock table when the coordinator is created.
     *
     * @return whether schema initialization is enabled
     */
    public boolean isInitializeSchema() {
      return initializeSchema;
    }

    /**
     * Sets whether to initialize the lock table when the coordinator is created.
     *
     * @param initializeSchema whether schema initialization is enabled
     */
    public void setInitializeSchema(boolean initializeSchema) {
      this.initializeSchema = initializeSchema;
    }

    /**
     * Returns the lock table name.
     *
     * @return lock table name
     */
    public String getTableName() {
      return tableName;
    }

    /**
     * Sets the lock table name.
     *
     * @param tableName lock table name
     */
    public void setTableName(String tableName) {
      this.tableName = tableName;
    }

    /**
     * Returns the logical lock name.
     *
     * @return logical lock name
     */
    public String getLockName() {
      return lockName;
    }

    /**
     * Sets the logical lock name.
     *
     * @param lockName logical lock name
     */
    public void setLockName(String lockName) {
      this.lockName = lockName;
    }

    /**
     * Returns the runtime owner id.
     *
     * @return runtime owner id
     */
    public String getOwnerId() {
      return ownerId;
    }

    /**
     * Sets the runtime owner id.
     *
     * @param ownerId runtime owner id
     */
    public void setOwnerId(String ownerId) {
      this.ownerId = ownerId;
    }

    /**
     * Returns lock lease duration.
     *
     * @return lock lease duration
     */
    public Duration getLeaseDuration() {
      return leaseDuration;
    }

    /**
     * Sets lock lease duration.
     *
     * @param leaseDuration lock lease duration
     */
    public void setLeaseDuration(Duration leaseDuration) {
      this.leaseDuration = leaseDuration;
    }

    /**
     * Returns lock acquisition timeout.
     *
     * @return lock acquisition timeout
     */
    public Duration getAcquireTimeout() {
      return acquireTimeout;
    }

    /**
     * Sets lock acquisition timeout.
     *
     * @param acquireTimeout lock acquisition timeout
     */
    public void setAcquireTimeout(Duration acquireTimeout) {
      this.acquireTimeout = acquireTimeout;
    }

    /**
     * Returns lock acquisition retry interval.
     *
     * @return lock acquisition retry interval
     */
    public Duration getRetryInterval() {
      return retryInterval;
    }

    /**
     * Sets lock acquisition retry interval.
     *
     * @param retryInterval lock acquisition retry interval
     */
    public void setRetryInterval(Duration retryInterval) {
      this.retryInterval = retryInterval;
    }
  }
}
