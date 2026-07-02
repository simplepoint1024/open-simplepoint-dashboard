/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

/**
 * Validates a plugin before it is installed.
 *
 * <p>Validators are intended for service-specific declarative contributions that
 * can be checked without mutating runtime state, such as menu, permission, remote
 * module, or feature conflicts.</p>
 */
public interface PluginInstallValidator {

  /**
   * Validator order. Lower values run first.
   *
   * @return order value
   */
  default int order() {
    return 0;
  }

  /**
   * Returns whether this validator should process the plugin.
   *
   * @param plugin plugin
   * @return true when supported
   */
  default boolean supports(Plugin plugin) {
    return plugin != null;
  }

  /**
   * Validates a plugin before install.
   *
   * @param plugin plugin candidate
   */
  void validate(Plugin plugin);
}
