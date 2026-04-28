/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

class JacksonAutoConfigurationTest {

  private final JacksonAutoConfiguration config = new JacksonAutoConfiguration();

  @Test
  void objectMapper_isNotNull() {
    ObjectMapper mapper = config.objectMapper();
    assertThat(mapper).isNotNull();
  }

  @Test
  void objectMapper_hasJavaTimeModule() {
    ObjectMapper mapper = config.objectMapper();
    assertThat(mapper.getRegisteredModuleIds()).anyMatch(id -> id.toString().contains("jackson-datatype-jsr310"));
  }

  @Test
  void objectMapper_doesNotFailOnUnknownProperties() {
    ObjectMapper mapper = config.objectMapper();
    assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
  }
}
