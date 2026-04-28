/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import org.junit.jupiter.api.Test;

class ApplicationClassLoaderTest {

  @Test
  void constructor_withUrlsAndParent() throws Exception {
    URL[] urls = new URL[]{};
    ApplicationClassLoader loader = new ApplicationClassLoader(urls, getClass().getClassLoader());
    assertThat(loader).isNotNull();
    loader.close();
  }

  @Test
  void constructor_withUrlsOnly() throws Exception {
    URL[] urls = new URL[]{};
    ApplicationClassLoader loader = new ApplicationClassLoader(urls);
    assertThat(loader).isNotNull();
    loader.close();
  }

  @Test
  void constructor_withNameUrlsAndParent() throws Exception {
    URL[] urls = new URL[]{};
    ApplicationClassLoader loader = new ApplicationClassLoader(
        "test-loader", urls, getClass().getClassLoader());
    assertThat(loader.getName()).isEqualTo("test-loader");
    loader.close();
  }

  @Test
  void addUrl_doesNotThrow() throws Exception {
    URL[] urls = new URL[]{};
    ApplicationClassLoader loader = new ApplicationClassLoader(urls, getClass().getClassLoader());
    URL testUrl = new URL("file:///tmp/");
    loader.addURL(testUrl);
    assertThat(loader.getURLs()).contains(testUrl);
    loader.close();
  }
}
