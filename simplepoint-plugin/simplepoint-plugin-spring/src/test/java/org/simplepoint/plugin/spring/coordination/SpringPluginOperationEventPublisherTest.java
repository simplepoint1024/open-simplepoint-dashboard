/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.PluginOperationContext;
import org.simplepoint.plugin.api.PluginOperationEvent;
import org.simplepoint.plugin.api.management.PluginOperation;
import org.simplepoint.plugin.api.management.PluginOperationOutcome;
import org.springframework.core.Ordered;

class SpringPluginOperationEventPublisherTest {

  @Test
  void coordinateDelegatesToCallback() throws Exception {
    SpringPluginOperationEventPublisher publisher =
        new SpringPluginOperationEventPublisher(event -> {
        });
    PluginOperationContext context = new PluginOperationContext(
        PluginOperation.INSTALL,
        List.of("org.example.plugin"),
        null,
        "operation-1",
        Instant.now());

    String result = publisher.coordinate(context, () -> "ok");

    assertThat(result).isEqualTo("ok");
  }

  @Test
  void publishForwardsPluginOperationEventToSpringPublisher() {
    AtomicReference<Object> published = new AtomicReference<>();
    SpringPluginOperationEventPublisher publisher =
        new SpringPluginOperationEventPublisher(published::set);
    PluginOperationEvent event = new PluginOperationEvent(
        PluginOperation.SUBMIT,
        PluginOperationOutcome.SUCCESS,
        List.of("org.example.plugin"),
        null,
        "operation-1",
        Instant.now(),
        null);

    publisher.publish(event);

    assertThat(published).hasValue(event);
    assertThat(publisher.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
  }
}
