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
import java.math.BigDecimal;
import java.util.Map;

class JacksonAutoConfigurationTest {

  private final JacksonAutoConfiguration config = new JacksonAutoConfiguration();

  @Test
  void legacyMapperAlsoKeepsDecimalPayloadsExact() throws Exception {
    var mapper = config.objectMapper();
    var amount = new BigDecimal("999999999999.123456789012");
    assertThat(mapper.writeValueAsString(Map.of("amount", amount, "count", 42)))
        .contains("\"amount\":\"999999999999.123456789012\"", "\"count\":42");
    assertThat(mapper.writeValueAsString(new BigDecimal("1E-12"))).isEqualTo("\"0.000000000001\"");
    assertThat(mapper.readValue("\"999999999999.123456789012\"", BigDecimal.class)).isEqualByComparingTo(amount);
    assertThat(mapper.readValue("999999999999.123456789012", BigDecimal.class)).isEqualByComparingTo(amount);
  }

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
