/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.exception;

import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.simplepoint.plugin.api.Plugin;

/**
 * An exception class for handling cases where a class already exists.
 * This exception is thrown when there is an attempt to create or register a class
 * that is already present in the system.
 */
@Getter
public class ClassExistException extends RuntimeException {

  /**
   * -- GETTER --
   *  Retrieves the data related to the conflicting class instances.
   *
   */
  private final Map<String, Set<Plugin.PluginInstance>> data;

  /**
   * Constructs a new ClassExistException instance.
   *
   * @param className the name of the class that already exists
   * @param data      the additional data related to the conflicting class instances
   */
  public ClassExistException(String className, Map<String, Set<Plugin.PluginInstance>> data) {
    super("class " + className + " already exist!");
    this.data = data;
  }

}
