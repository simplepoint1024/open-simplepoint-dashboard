/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.management;

/**
 * Plugin management operation type.
 */
public enum PluginOperation {

  /**
   * Install a plugin artifact.
   */
  INSTALL,

  /**
   * Execute queued plugin registration tasks.
   */
  SUBMIT,

  /**
   * Upgrade an installed plugin artifact.
   */
  UPGRADE,

  /**
   * Enable a disabled plugin.
   */
  ENABLE,

  /**
   * Disable an enabled plugin.
   */
  DISABLE,

  /**
   * Uninstall a plugin.
   */
  UNINSTALL
}
