/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.PluginOperationAuditRecorder;
import org.simplepoint.plugin.api.management.PluginOperation;
import org.simplepoint.plugin.api.management.PluginOperationAudit;
import org.simplepoint.plugin.api.management.PluginOperationOutcome;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Records plugin operation audits around lifecycle operations.
 */
final class PluginOperationAuditSupport {

  private final PluginOperationAuditRecorder recorder;

  PluginOperationAuditSupport(PluginOperationAuditRecorder recorder) {
    this.recorder = recorder;
  }

  List<PluginOperationAudit> list() {
    return recorder.list();
  }

  <T> T audit(
      PluginOperation operation,
      URI path,
      String pluginId,
      PluginOperationCall<T> call
  ) throws Exception {
    Instant startedAt = Instant.now();
    long startedNanos = System.nanoTime();
    try {
      T result = call.execute();
      record(
          operation,
          PluginOperationOutcome.SUCCESS,
          result instanceof Plugin plugin ? plugin : null,
          path,
          pluginId,
          startedAt,
          startedNanos,
          null);
      return result;
    } catch (Exception e) {
      record(operation, PluginOperationOutcome.FAILURE, null, path, pluginId, startedAt, startedNanos, e);
      throw e;
    }
  }

  <T> T auditRuntime(
      PluginOperation operation,
      String pluginId,
      Supplier<Plugin> fallbackPlugin,
      PluginOperationRuntimeCall<T> call
  ) {
    Instant startedAt = Instant.now();
    long startedNanos = System.nanoTime();
    try {
      T result = call.execute();
      record(
          operation,
          PluginOperationOutcome.SUCCESS,
          result instanceof Plugin plugin ? plugin : fallbackPlugin.get(),
          null,
          pluginId,
          startedAt,
          startedNanos,
          null);
      return result;
    } catch (RuntimeException e) {
      record(operation, PluginOperationOutcome.FAILURE, fallbackPlugin.get(), null, pluginId, startedAt, startedNanos, e);
      throw e;
    }
  }

  void record(
      PluginOperation operation,
      PluginOperationOutcome outcome,
      Plugin plugin,
      URI path,
      String fallbackPluginId,
      Instant startedAt,
      long startedNanos,
      Exception failure
  ) {
    PluginManifest manifest = plugin == null ? null : plugin.manifest();
    PluginArtifact artifact = plugin == null ? null : plugin.artifact();
    URI artifactPath = artifact == null ? path : artifact.uri();
    recorder.record(new PluginOperationAudit(
        UUID.randomUUID().toString(),
        operation,
        outcome,
        manifest == null ? fallbackPluginId : manifest.getId(),
        manifest == null ? null : manifest.getVersion(),
        artifactPath,
        artifact,
        startedAt,
        Instant.now(),
        Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L),
        failure == null ? null : failure.getMessage()
    ));
  }

  @FunctionalInterface
  interface PluginOperationCall<T> {

    T execute() throws Exception;
  }

  @FunctionalInterface
  interface PluginOperationRuntimeCall<T> {

    T execute();
  }
}
