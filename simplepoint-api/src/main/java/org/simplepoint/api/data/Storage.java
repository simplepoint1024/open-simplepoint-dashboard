/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.api.data;

import java.util.List;

/**
 * storage.
 *
 * @param <T> obj.
 */
public interface Storage<T> {
  /**
   * obj.
   *
   * @param plugin plugin information
   * @return obj
   */
  T save(T plugin);

  /**
   * remove.
   *
   * @param packageName packageName
   */
  void remove(String packageName);

  /**
   * find.
   *
   * @param packageName packageName
   * @return package information
   */
  T find(String packageName);

  /**
   * list.
   *
   * @return list
   */
  List<T> list();
}
