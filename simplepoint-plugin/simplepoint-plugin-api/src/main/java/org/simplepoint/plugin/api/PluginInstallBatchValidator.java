/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

import java.util.List;

/**
 * Validates a batch of plugin candidates before they are installed.
 *
 * <p>Batch validators catch conflicts that only appear when multiple plugin
 * archives are planned together, such as duplicate declarative resources or
 * cross-plugin contribution references.</p>
 */
public interface PluginInstallBatchValidator {

  /**
   * Validator order. Lower values run first.
   *
   * @return order value
   */
  default int order() {
    return 0;
  }

  /**
   * Returns whether this validator should process the candidate batch.
   *
   * @param plugins plugin candidates
   * @return true when supported
   */
  default boolean supports(List<Plugin> plugins) {
    return plugins != null && !plugins.isEmpty();
  }

  /**
   * Validates plugin candidates before batch install.
   *
   * @param plugins plugin candidates
   */
  void validate(List<Plugin> plugins);
}
