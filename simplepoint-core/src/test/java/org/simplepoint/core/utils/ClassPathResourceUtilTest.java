/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassPathResourceUtilTest {

  /** Simple POJO to deserialize test JSON {"value":"hello"} */
  static class Item {
    public String value;
  }

  @Test
  void readJson_loadsJsonFilesFromDirectory() throws IOException {
    Map<String, Item> result = ClassPathResourceUtil.readJson("test-scan", Item.class);
    assertThat(result).containsKey("item");
    assertThat(result.get("item").value).isEqualTo("hello");
  }

  @Test
  void readJson_returnsEmpty_whenNoFilesExist() throws IOException {
    Map<String, Item> result = ClassPathResourceUtil.readJson("non-existent-dir", Item.class);
    assertThat(result).isEmpty();
  }

  @Test
  void readJsonPathMap_loadsNestedJsonByLanguage() throws IOException {
    Map<String, Map<String, Map<String, String>>> result =
        ClassPathResourceUtil.readJsonPathMap("test-i18n");
    assertThat(result).containsKey("en");
    assertThat(result.get("en")).containsKey("messages");
    assertThat(result.get("en").get("messages")).containsEntry("greeting", "Hello");
  }

  @Test
  void readJsonPathMap_returnsEmpty_whenNoFilesExist() throws IOException {
    Map<String, Map<String, Map<String, String>>> result =
        ClassPathResourceUtil.readJsonPathMap("non-existent-i18n-dir");
    assertThat(result).isEmpty();
  }
}
