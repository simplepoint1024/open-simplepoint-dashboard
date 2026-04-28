/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.base.BaseDetailsService;
import org.springframework.context.ApplicationContext;

class SpringBeanDetailsProviderServiceTest {

  private SpringBeanDetailsProviderService service;
  private ApplicationContext applicationContext;

  @BeforeEach
  void setUp() {
    service = new SpringBeanDetailsProviderService();
    applicationContext = mock(ApplicationContext.class);
    service.setApplicationContext(applicationContext);
  }

  @Test
  void getDialect_returnsBean() {
    BaseDetailsService mockDialect = mock(BaseDetailsService.class);
    when(applicationContext.getBean(BaseDetailsService.class)).thenReturn(mockDialect);

    BaseDetailsService result = service.getDialect(BaseDetailsService.class);

    assertThat(result).isSameAs(mockDialect);
  }

  @Test
  void getDialects_returnsAllBeansOfType() {
    BaseDetailsService d1 = mock(BaseDetailsService.class);
    BaseDetailsService d2 = mock(BaseDetailsService.class);
    when(applicationContext.getBeansOfType(BaseDetailsService.class))
        .thenReturn(Map.of("d1", d1, "d2", d2));

    Collection<BaseDetailsService> result = service.getDialects(BaseDetailsService.class);

    assertThat(result).containsExactlyInAnyOrder(d1, d2);
  }
}
