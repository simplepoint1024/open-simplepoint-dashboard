/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.api.PluginOperationAuditRecorder;
import org.simplepoint.plugin.api.management.PluginOperationAudit;

/**
 * Bounded in-memory plugin operation audit recorder.
 */
public final class InMemoryPluginOperationAuditRecorder implements PluginOperationAuditRecorder {

  private static final int DEFAULT_LIMIT = 200;

  private final int limit;
  private final ArrayDeque<PluginOperationAudit> audits = new ArrayDeque<>();

  /**
   * Creates a recorder with the default entry limit.
   */
  public InMemoryPluginOperationAuditRecorder() {
    this(DEFAULT_LIMIT);
  }

  /**
   * Creates a recorder with a custom entry limit.
   *
   * @param limit max audit entries to retain
   */
  public InMemoryPluginOperationAuditRecorder(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("Audit limit must be greater than zero");
    }
    this.limit = limit;
  }

  @Override
  public synchronized void record(PluginOperationAudit audit) {
    if (audit == null) {
      return;
    }
    while (audits.size() >= limit) {
      audits.removeFirst();
    }
    audits.addLast(audit);
  }

  @Override
  public synchronized List<PluginOperationAudit> list() {
    return List.copyOf(new ArrayList<>(audits));
  }
}
