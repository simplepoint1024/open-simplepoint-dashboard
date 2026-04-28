/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.constants;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConstantsTest {

  @Test
  void icons_canInstantiate() {
    assertThat(new Icons()).isNotNull();
  }

  @Test
  void icons_constantsHaveExpectedValues() {
    assertThat(Icons.PLUS_CIRCLE).isEqualTo("PlusCircleOutlined");
    assertThat(Icons.EDIT).isEqualTo("EditOutlined");
    assertThat(Icons.MINUS_CIRCLE).isEqualTo("MinusCircleOutlined");
    assertThat(Icons.SAFETY_OUTLINED).isEqualTo("SafetyOutlined");
  }

  @Test
  void publicButtonKeys_canInstantiate() {
    assertThat(new PublicButtonKeys()).isNotNull();
  }

  @Test
  void publicButtonKeys_constantsHaveExpectedValues() {
    assertThat(PublicButtonKeys.ADD_KEY).isEqualTo("add");
    assertThat(PublicButtonKeys.EDIT_KEY).isEqualTo("edit");
    assertThat(PublicButtonKeys.DELETE_KEY).isEqualTo("delete");
    assertThat(PublicButtonKeys.ADD_TITLE).isEqualTo("i18n:table.button.create");
    assertThat(PublicButtonKeys.EDIT_TITLE).isEqualTo("i18n:table.button.edit");
    assertThat(PublicButtonKeys.DELETE_TITLE).isEqualTo("i18n:table.button.delete");
  }
}
