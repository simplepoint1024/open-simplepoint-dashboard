/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.api.PluginTaskStore;
import org.simplepoint.plugin.api.management.PluginTaskSnapshot;

/**
 * Bounded in-memory plugin task store.
 */
public final class InMemoryPluginTaskStore implements PluginTaskStore {

  private static final int DEFAULT_LIMIT = 500;

  private final int limit;
  private final Map<String, PluginTaskSnapshot> tasks = new LinkedHashMap<>();

  /**
   * Creates a task store with the default entry limit.
   */
  public InMemoryPluginTaskStore() {
    this(DEFAULT_LIMIT);
  }

  /**
   * Creates a task store with a custom entry limit.
   *
   * @param limit max task snapshots to retain
   */
  public InMemoryPluginTaskStore(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("Task store limit must be greater than zero");
    }
    this.limit = limit;
  }

  @Override
  public synchronized void save(PluginTaskSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    tasks.remove(snapshot.id());
    while (tasks.size() >= limit) {
      String firstKey = tasks.keySet().iterator().next();
      tasks.remove(firstKey);
    }
    tasks.put(snapshot.id(), snapshot);
  }

  @Override
  public synchronized List<PluginTaskSnapshot> list() {
    return List.copyOf(new ArrayList<>(tasks.values()));
  }
}
