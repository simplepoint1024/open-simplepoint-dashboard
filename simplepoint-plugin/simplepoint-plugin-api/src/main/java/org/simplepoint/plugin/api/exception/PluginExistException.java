/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.exception;

import lombok.Getter;
import org.simplepoint.plugin.api.Plugin;

/**
 * An exception class for handling cases where a plugin already exists.
 * This exception is thrown when there is an attempt to register or install a plugin
 * that is already present in the system.
 */
@Getter
public class PluginExistException extends Exception {

  private final Plugin plugin;

  /**
   * Constructs a new PluginExistException instance.
   *
   * @param msg    the error message describing the conflict
   * @param plugin the conflicting Plugin instance
   */
  public PluginExistException(String msg, Plugin plugin) {
    super(msg);
    this.plugin = plugin;
  }
}
