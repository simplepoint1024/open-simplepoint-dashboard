/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

class ApplicationContextProviderTest {

  private ApplicationContext previousContext;

  @BeforeEach
  void saveContext() throws Exception {
    Field field = ApplicationContextProvider.class.getDeclaredField("context");
    field.setAccessible(true);
    previousContext = (ApplicationContext) field.get(null);
  }

  @AfterEach
  void restoreContext() throws Exception {
    Field field = ApplicationContextProvider.class.getDeclaredField("context");
    field.setAccessible(true);
    field.set(null, previousContext);
  }

  @Test
  void setApplicationContext_makesContextAvailable() throws Exception {
    ApplicationContext ctx = mock(ApplicationContext.class);
    ApplicationContextProvider provider = new ApplicationContextProvider();
    provider.setApplicationContext(ctx);
    assertThat(ApplicationContextProvider.getApplicationContext()).isSameAs(ctx);
  }

  @Test
  void getBean_byName_delegatesToContext() throws Exception {
    ApplicationContext ctx = mock(ApplicationContext.class);
    when(ctx.getBean("myBean")).thenReturn("beanValue");
    ApplicationContextProvider provider = new ApplicationContextProvider();
    provider.setApplicationContext(ctx);

    Object result = ApplicationContextProvider.getBean("myBean");
    assertThat(result).isEqualTo("beanValue");
  }

  @Test
  void getBean_byType_delegatesToContext() throws Exception {
    ApplicationContext ctx = mock(ApplicationContext.class);
    when(ctx.getBean(String.class)).thenReturn("typed-bean");
    ApplicationContextProvider provider = new ApplicationContextProvider();
    provider.setApplicationContext(ctx);

    String result = ApplicationContextProvider.getBean(String.class);
    assertThat(result).isEqualTo("typed-bean");
  }
}
