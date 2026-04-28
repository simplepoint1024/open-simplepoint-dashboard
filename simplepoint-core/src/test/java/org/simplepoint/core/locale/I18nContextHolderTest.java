/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.ApplicationContextProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

class I18nContextHolderTest {

  private ApplicationContext savedContext;

  @BeforeEach
  void saveApplicationContext() throws Exception {
    Field f = ApplicationContextProvider.class.getDeclaredField("context");
    f.setAccessible(true);
    savedContext = (ApplicationContext) f.get(null);
  }

  @AfterEach
  void restoreApplicationContext() throws Exception {
    Field f = ApplicationContextProvider.class.getDeclaredField("context");
    f.setAccessible(true);
    f.set(null, savedContext);
  }

  private void setMockContext(MessageSource ms) throws Exception {
    ApplicationContext ctx = mock(ApplicationContext.class);
    when(ctx.getBean(MessageSource.class)).thenReturn(ms);
    Field f = ApplicationContextProvider.class.getDeclaredField("context");
    f.setAccessible(true);
    f.set(null, ctx);
  }

  @Test
  void disableI18n_returnsFalse_whenAttributesNull() {
    assertThat(I18nContextHolder.disableI18n(null)).isFalse();
  }

  @Test
  void disableI18n_returnsFalse_whenKeyAbsent() {
    assertThat(I18nContextHolder.disableI18n(Map.of())).isFalse();
  }

  @Test
  void disableI18n_returnsFalse_whenEnableI18nIsTrue() {
    assertThat(I18nContextHolder.disableI18n(Map.of("enable.i18n", "true"))).isFalse();
  }

  @Test
  void disableI18n_returnsTrue_whenEnableI18nIsFalse() {
    assertThat(I18nContextHolder.disableI18n(Map.of("enable.i18n", "false"))).isTrue();
  }

  @Test
  void disableI18n_isCaseInsensitive() {
    assertThat(I18nContextHolder.disableI18n(Map.of("enable.i18n", "FALSE"))).isTrue();
    assertThat(I18nContextHolder.disableI18n(Map.of("enable.i18n", "False"))).isTrue();
  }

  // Use Integer (non-Collection) to avoid ambiguity with collection overload

  @Test
  void localize_single_doesNothingWhenDisabled() {
    List<Integer> calls = new ArrayList<>();
    Function<Integer, String> getter = Object::toString;
    BiConsumer<Integer, String> setter = (obj, v) -> calls.add(obj);
    I18nContextHolder.localize(42, getter, setter, true);
    assertThat(calls).isEmpty();
  }

  @Test
  void localize_single_doesNothingWhenObjectNull() {
    List<Integer> calls = new ArrayList<>();
    Function<Integer, String> getter = Object::toString;
    BiConsumer<Integer, String> setter = (obj, v) -> calls.add(obj);
    I18nContextHolder.localize((Integer) null, getter, setter, false);
    assertThat(calls).isEmpty();
  }

  @Test
  void localize_single_doesNothingWhenGetterNull() {
    List<Integer> calls = new ArrayList<>();
    BiConsumer<Integer, String> setter = (obj, v) -> calls.add(obj);
    I18nContextHolder.localize(42, (Function<Integer, String>) null, setter, false);
    assertThat(calls).isEmpty();
  }

  @Test
  void localize_single_doesNothingWhenSetterNull() {
    Function<Integer, String> getter = Object::toString;
    I18nContextHolder.localize(42, getter, (BiConsumer<Integer, String>) null, false);
  }

  // Collection overload tests — use explicit Collection<String> variable

  @Test
  void localize_collection_doesNothingWhenDisabled() {
    List<String> calls = new ArrayList<>();
    Collection<String> items = List.of("a", "b");
    Function<String, String> getter = s -> s;
    BiConsumer<String, String> setter = (s, v) -> calls.add(v);
    I18nContextHolder.localize(items, getter, setter, true);
    assertThat(calls).isEmpty();
  }

  @Test
  void localize_collection_doesNothingWhenCollectionNull() {
    List<String> calls = new ArrayList<>();
    Function<String, String> getter = s -> s;
    BiConsumer<String, String> setter = (s, v) -> calls.add(v);
    I18nContextHolder.localize((Collection<String>) null, getter, setter, false);
    assertThat(calls).isEmpty();
  }

  @Test
  void localize_collection_doesNothingWhenSetterNull() {
    Collection<String> items = List.of("a");
    Function<String, String> getter = s -> s;
    I18nContextHolder.localize(items, getter, (BiConsumer<String, String>) null, false);
  }

  @Test
  void getLocale_returnsCurrentLocale() {
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    try {
      assertThat(I18nContextHolder.getLocale()).isEqualTo(Locale.ENGLISH);
    } finally {
      LocaleContextHolder.resetLocaleContext();
    }
  }

  @Test
  void getLocale_returnsDefaultLocale_whenNotExplicitlySet() {
    LocaleContextHolder.resetLocaleContext();
    assertThat(I18nContextHolder.getLocale()).isNotNull();
  }

  @Test
  void getTimeZone_returnsCurrentTimeZone() {
    TimeZone tz = TimeZone.getTimeZone("UTC");
    LocaleContextHolder.setTimeZone(tz);
    try {
      assertThat(I18nContextHolder.getTimeZone()).isEqualTo(tz);
    } finally {
      LocaleContextHolder.resetLocaleContext();
    }
  }

  @Test
  void constructor_throwsAssertionError() {
    assertThatThrownBy(() -> {
      var c = I18nContextHolder.class.getDeclaredConstructor();
      c.setAccessible(true);
      c.newInstance();
    }).hasCauseInstanceOf(AssertionError.class);
  }

  @Test
  void getMessageSource_returnsMessageSourceBean() throws Exception {
    MessageSource ms = mock(MessageSource.class);
    setMockContext(ms);
    assertThat(I18nContextHolder.getMessageSource()).isSameAs(ms);
  }

  @Test
  void getMessage_withDefault_delegatesToMessageSource() throws Exception {
    MessageSource ms = mock(MessageSource.class);
    when(ms.getMessage(eq("my.code"), any(Object[].class), eq("default"), any()))
        .thenReturn("resolved");
    setMockContext(ms);
    assertThat(I18nContextHolder.getMessage("my.code", "default")).isEqualTo("resolved");
  }

  @Test
  void getMessage_noDefault_delegatesToMessageSource() throws Exception {
    MessageSource ms = mock(MessageSource.class);
    when(ms.getMessage(eq("my.code"), any(Object[].class), any(Locale.class)))
        .thenReturn("resolved");
    setMockContext(ms);
    assertThat(I18nContextHolder.getMessage("my.code")).isEqualTo("resolved");
  }

  @Test
  void localize_single_callsGetMessage_whenLabelNonEmpty() throws Exception {
    MessageSource ms = mock(MessageSource.class);
    when(ms.getMessage(eq("my.label"), any(Object[].class), any(Locale.class)))
        .thenReturn("localized");
    setMockContext(ms);

    StringBuilder result = new StringBuilder();
    I18nContextHolder.localize("my.label", s -> s, (s, v) -> result.append(v), false);
    assertThat(result.toString()).isEqualTo("localized");
  }

  @Test
  void localize_single_doesNothing_whenLabelNull() throws Exception {
    MessageSource ms = mock(MessageSource.class);
    setMockContext(ms);
    StringBuilder result = new StringBuilder();
    I18nContextHolder.localize("dummy", (String s) -> null, (s, v) -> result.append(v), false);
    assertThat(result.toString()).isEmpty();
  }

  @Test
  void localize_collection_callsGetMessage_forEachNonEmptyLabel() throws Exception {
    MessageSource ms = mock(MessageSource.class);
    when(ms.getMessage(any(String.class), any(Object[].class), any(Locale.class)))
        .thenAnswer(inv -> "localized_" + inv.getArgument(0));
    setMockContext(ms);

    Collection<String> items = List.of("key1", "key2");
    List<String> results = new ArrayList<>();
    Function<String, String> getter = s -> s;
    BiConsumer<String, String> setter = (s, v) -> results.add(v);
    I18nContextHolder.localize(items, getter, setter, false);
    assertThat(results).containsExactly("localized_key1", "localized_key2");
  }
}
