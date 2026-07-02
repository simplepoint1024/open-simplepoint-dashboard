/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

/**
 * Runtime state of a plugin managed by the plugin control plane.
 */
public enum PluginStatus {

  /**
   * The plugin archive has been read and validated.
   */
  RESOLVED,

  /**
   * The plugin has been installed into the runtime and is waiting for submitted tasks.
   */
  INSTALLED,

  /**
   * The plugin registration tasks and lifecycle contributions have completed.
   */
  ENABLED,

  /**
   * The plugin has been disabled or is being uninstalled.
   */
  DISABLED,

  /**
   * The plugin failed during install, submit, or lifecycle processing.
   */
  FAILED
}
