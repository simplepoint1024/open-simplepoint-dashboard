/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.simplepoint.plugin.api.PluginOperationAuditRecorder;
import org.simplepoint.plugin.api.management.PluginOperationAudit;

/**
 * Records audit entries into multiple recorders.
 */
public final class CompositePluginOperationAuditRecorder implements PluginOperationAuditRecorder {

  private final List<PluginOperationAuditRecorder> recorders;

  private CompositePluginOperationAuditRecorder(Collection<PluginOperationAuditRecorder> recorders) {
    this.recorders = recorders == null ? List.of() : List.copyOf(recorders);
  }

  /**
   * Creates a recorder from the supplied recorders.
   *
   * @param recorders audit recorders
   * @return a composite recorder
   */
  public static PluginOperationAuditRecorder of(Collection<PluginOperationAuditRecorder> recorders) {
    if (recorders == null || recorders.isEmpty()) {
      return new InMemoryPluginOperationAuditRecorder();
    }
    return new CompositePluginOperationAuditRecorder(recorders);
  }

  @Override
  public void record(PluginOperationAudit audit) {
    for (PluginOperationAuditRecorder recorder : recorders) {
      recorder.record(audit);
    }
  }

  @Override
  public List<PluginOperationAudit> list() {
    return recorders.stream()
        .flatMap(recorder -> recorder.list().stream())
        .sorted(Comparator.comparing(PluginOperationAudit::startedAt))
        .toList();
  }
}
