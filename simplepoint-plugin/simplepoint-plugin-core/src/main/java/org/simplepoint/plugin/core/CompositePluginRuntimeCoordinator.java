/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.api.PluginOperationCallback;
import org.simplepoint.plugin.api.PluginOperationContext;
import org.simplepoint.plugin.api.PluginOperationEvent;
import org.simplepoint.plugin.api.PluginRuntimeCoordinator;

/**
 * Composite runtime coordinator that nests operation boundaries in order and
 * publishes events to all configured coordinators.
 */
public final class CompositePluginRuntimeCoordinator implements PluginRuntimeCoordinator {

  private final List<PluginRuntimeCoordinator> coordinators;

  private CompositePluginRuntimeCoordinator(List<PluginRuntimeCoordinator> coordinators) {
    this.coordinators = List.copyOf(Objects.requireNonNullElse(coordinators, List.of()));
  }

  /**
   * Creates a coordinator from ordered coordinator implementations.
   *
   * @param coordinators configured coordinators
   * @return composite coordinator or no-op coordinator when empty
   */
  public static PluginRuntimeCoordinator of(List<PluginRuntimeCoordinator> coordinators) {
    List<PluginRuntimeCoordinator> filtered = Objects.requireNonNullElse(coordinators, List.<PluginRuntimeCoordinator>of())
        .stream()
        .filter(Objects::nonNull)
        .toList();
    if (filtered.isEmpty()) {
      return NoopPluginRuntimeCoordinator.INSTANCE;
    }
    if (filtered.size() == 1) {
      return filtered.get(0);
    }
    return new CompositePluginRuntimeCoordinator(filtered);
  }

  @Override
  public <T> T coordinate(PluginOperationContext context, PluginOperationCallback<T> callback) throws Exception {
    return coordinateAt(0, context, callback);
  }

  @Override
  public void publish(PluginOperationEvent event) {
    for (PluginRuntimeCoordinator coordinator : coordinators) {
      coordinator.publish(event);
    }
  }

  private <T> T coordinateAt(
      int index,
      PluginOperationContext context,
      PluginOperationCallback<T> callback
  ) throws Exception {
    if (index >= coordinators.size()) {
      return callback.execute();
    }
    return coordinators.get(index).coordinate(context, () -> coordinateAt(index + 1, context, callback));
  }
}
