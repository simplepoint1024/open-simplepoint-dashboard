/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EnumsTest {

  @Test
  void buttonType_valuesExist() {
    assertThat(ButtonType.values()).isNotEmpty();
    assertThat(ButtonType.PRIMARY.getValue()).isEqualTo("primary");
    assertThat(ButtonType.DEFAULT.getValue()).isEqualTo("default");
    assertThat(ButtonType.DASHED.getValue()).isEqualTo("dashed");
    assertThat(ButtonType.LINK.getValue()).isEqualTo("link");
    assertThat(ButtonType.TEXT.getValue()).isEqualTo("text");
  }

  @Test
  void buttonType_toString_returnsValue() {
    assertThat(ButtonType.PRIMARY.toString()).isEqualTo("primary");
    assertThat(ButtonType.TEXT.toString()).isEqualTo("text");
  }

  @Test
  void buttonType_valueOf_works() {
    assertThat(ButtonType.valueOf("PRIMARY")).isEqualTo(ButtonType.PRIMARY);
  }

  @Test
  void buttonVariantTypes_valuesExist() {
    assertThat(ButtonVariantTypes.values()).isNotEmpty();
    assertThat(ButtonVariantTypes.OUTLINED.getValue()).isEqualTo("outlined");
    assertThat(ButtonVariantTypes.DASHED.getValue()).isEqualTo("dashed");
    assertThat(ButtonVariantTypes.SOLID.getValue()).isEqualTo("solid");
    assertThat(ButtonVariantTypes.FILLED.getValue()).isEqualTo("filled");
    assertThat(ButtonVariantTypes.TEXT.getValue()).isEqualTo("text");
    assertThat(ButtonVariantTypes.LINK.getValue()).isEqualTo("link");
  }

  @Test
  void buttonVariantTypes_toString_returnsValue() {
    assertThat(ButtonVariantTypes.SOLID.toString()).isEqualTo("solid");
  }

  @Test
  void buttonVariantTypes_valueOf_works() {
    assertThat(ButtonVariantTypes.valueOf("SOLID")).isEqualTo(ButtonVariantTypes.SOLID);
  }

  @Test
  void httpMethods_allValuesAccessible() {
    assertThat(HttpMethods.values()).containsExactlyInAnyOrder(
        HttpMethods.GET, HttpMethods.POST, HttpMethods.PUT, HttpMethods.DELETE,
        HttpMethods.PATCH, HttpMethods.HEAD, HttpMethods.OPTIONS, HttpMethods.TRACE
    );
  }

  @Test
  void httpMethods_valueOf_works() {
    assertThat(HttpMethods.valueOf("GET")).isEqualTo(HttpMethods.GET);
    assertThat(HttpMethods.valueOf("POST")).isEqualTo(HttpMethods.POST);
  }
}
