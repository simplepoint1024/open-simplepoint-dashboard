/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

/**
 * Handles plugin-level lifecycle contributions.
 *
 * <p>Instance handlers manage runtime classes such as Spring beans and MVC controllers.
 * Lifecycle handlers manage declarative resources from {@code plugin.yaml}, such as menus,
 * permissions, frontend remotes, i18n bundles, or service-specific registrations.</p>
 */
public interface PluginLifecycleHandler {

  /**
   * Handler order. Lower values run first on install and later on uninstall.
   *
   * @return order value
   */
  default int order() {
    return 0;
  }

  /**
   * Returns whether this handler should process the plugin.
   *
   * @param plugin plugin
   * @return true when supported
   */
  default boolean supports(Plugin plugin) {
    return plugin != null;
  }

  /**
   * Called after plugin classes are registered and before the install submit cycle completes.
   *
   * @param plugin installed plugin
   */
  default void installed(Plugin plugin) {
  }

  /**
   * Called before plugin classes are rolled back during uninstall.
   *
   * @param plugin plugin being uninstalled
   */
  default void uninstalling(Plugin plugin) {
  }
}
