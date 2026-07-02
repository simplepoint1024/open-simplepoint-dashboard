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
   * @param data data item
   * @return obj
   */
  T save(T data);

  /**
   * remove.
   *
   * @param key storage key
   */
  void remove(String key);

  /**
   * find.
   *
   * @param key storage key
   * @return stored data item
   */
  T find(String key);

  /**
   * list.
   *
   * @return list
   */
  List<T> list();
}
