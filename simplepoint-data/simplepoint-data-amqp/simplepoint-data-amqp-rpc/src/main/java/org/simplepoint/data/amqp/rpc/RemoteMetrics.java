/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.rpc;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Micrometer metrics helpers for AMQP RPC client and server flows.
 */
final class RemoteMetrics {

  static final String CLIENT_REQUESTS = "simplepoint.amqp.rpc.client.requests";

  static final String CLIENT_LATENCY = "simplepoint.amqp.rpc.client.latency";

  static final String SERVER_REQUESTS = "simplepoint.amqp.rpc.server.requests";

  static final String SERVER_LATENCY = "simplepoint.amqp.rpc.server.latency";

  private RemoteMetrics() {
  }

  static void recordClient(@Nullable final MeterRegistry registry, final String target,
                           final Method method, final String outcome, final long durationNanos) {
    if (registry == null) {
      return;
    }
    List<Tag> tags = List.of(
        Tag.of("target", valueOrUnknown(target)),
        Tag.of("interface", method.getDeclaringClass().getName()),
        Tag.of("method", method.getName()),
        Tag.of("outcome", valueOrUnknown(outcome))
    );
    Counter.builder(CLIENT_REQUESTS).tags(tags).register(registry).increment();
    Timer.builder(CLIENT_LATENCY).tags(tags).register(registry).record(durationNanos, TimeUnit.NANOSECONDS);
  }

  static void recordServer(@Nullable final MeterRegistry registry,
                           final RemoteProtocol.RemoteMethodDescriptor descriptor,
                           final String outcome, final long durationNanos) {
    if (registry == null) {
      return;
    }
    List<Tag> tags = List.of(
        Tag.of("interface", valueOrUnknown(descriptor.interfaceName())),
        Tag.of("method", valueOrUnknown(descriptor.methodName())),
        Tag.of("outcome", valueOrUnknown(outcome))
    );
    Counter.builder(SERVER_REQUESTS).tags(tags).register(registry).increment();
    Timer.builder(SERVER_LATENCY).tags(tags).register(registry).record(durationNanos, TimeUnit.NANOSECONDS);
  }

  private static String valueOrUnknown(final String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }
}
