/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.configuration;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for plugin runtime event propagation.
 */
@ConfigurationProperties(prefix = "plugin.runtime-events")
public class PluginRuntimeEventProperties {

  private final Jdbc jdbc = new Jdbc();

  /**
   * Returns JDBC runtime event configuration.
   *
   * @return JDBC runtime event configuration
   */
  public Jdbc getJdbc() {
    return jdbc;
  }

  /**
   * JDBC runtime event configuration.
   */
  public static class Jdbc {

    private boolean enabled;
    private boolean initializeSchema = true;
    private boolean relayEnabled = true;
    private boolean replayExisting;
    private String tableName = "sp_plugin_runtime_event";
    private String originId = "node-" + UUID.randomUUID();
    private Duration pollInterval = Duration.ofSeconds(2);
    private int batchSize = 100;

    /**
     * Whether JDBC runtime event recording is enabled.
     *
     * @return whether JDBC runtime event recording is enabled
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Sets whether JDBC runtime event recording is enabled.
     *
     * @param enabled whether JDBC runtime event recording is enabled
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * Whether to initialize the runtime event table when the store is created.
     *
     * @return whether schema initialization is enabled
     */
    public boolean isInitializeSchema() {
      return initializeSchema;
    }

    /**
     * Sets whether to initialize the runtime event table when the store is created.
     *
     * @param initializeSchema whether schema initialization is enabled
     */
    public void setInitializeSchema(boolean initializeSchema) {
      this.initializeSchema = initializeSchema;
    }

    /**
     * Whether to relay JDBC events from other nodes to the local Spring event bus.
     *
     * @return whether JDBC event relay is enabled
     */
    public boolean isRelayEnabled() {
      return relayEnabled;
    }

    /**
     * Sets whether to relay JDBC events from other nodes to the local Spring event bus.
     *
     * @param relayEnabled whether JDBC event relay is enabled
     */
    public void setRelayEnabled(boolean relayEnabled) {
      this.relayEnabled = relayEnabled;
    }

    /**
     * Whether the relay should replay existing rows when it starts.
     *
     * @return whether existing events should be replayed
     */
    public boolean isReplayExisting() {
      return replayExisting;
    }

    /**
     * Sets whether the relay should replay existing rows when it starts.
     *
     * @param replayExisting whether existing events should be replayed
     */
    public void setReplayExisting(boolean replayExisting) {
      this.replayExisting = replayExisting;
    }

    /**
     * Returns the runtime event table name.
     *
     * @return runtime event table name
     */
    public String getTableName() {
      return tableName;
    }

    /**
     * Sets the runtime event table name.
     *
     * @param tableName runtime event table name
     */
    public void setTableName(String tableName) {
      this.tableName = tableName;
    }

    /**
     * Returns this node's event origin id.
     *
     * @return event origin id
     */
    public String getOriginId() {
      return originId;
    }

    /**
     * Sets this node's event origin id.
     *
     * @param originId event origin id
     */
    public void setOriginId(String originId) {
      this.originId = originId;
    }

    /**
     * Returns relay polling interval.
     *
     * @return relay polling interval
     */
    public Duration getPollInterval() {
      return pollInterval;
    }

    /**
     * Sets relay polling interval.
     *
     * @param pollInterval relay polling interval
     */
    public void setPollInterval(Duration pollInterval) {
      this.pollInterval = pollInterval;
    }

    /**
     * Returns maximum number of events fetched per relay poll.
     *
     * @return relay batch size
     */
    public int getBatchSize() {
      return batchSize;
    }

    /**
     * Sets maximum number of events fetched per relay poll.
     *
     * @param batchSize relay batch size
     */
    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }
  }
}
