/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.management;

/**
 * Plugin operation dry-run issue category.
 */
public enum PluginInstallPlanIssueCode {

  /**
   * The source path is invalid for installation planning.
   */
  DIRECTORY_INVALID,

  /**
   * A plugin artifact cannot be read or validated.
   */
  DESCRIPTOR_INVALID,

  /**
   * A readable plugin artifact fails install-time validation.
   */
  INSTALL_VALIDATION_FAILED,

  /**
   * A readable plugin artifact fails upgrade-time validation.
   */
  UPGRADE_VALIDATION_FAILED,

  /**
   * Declared plugin dependencies cannot be resolved.
   */
  DEPENDENCY_RESOLUTION_FAILED
}
