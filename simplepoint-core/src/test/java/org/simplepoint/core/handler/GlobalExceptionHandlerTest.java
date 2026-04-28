/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlobalExceptionHandlerTest {

  @Test
  void canInstantiate() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    assertThat(handler).isNotNull();
  }
}
