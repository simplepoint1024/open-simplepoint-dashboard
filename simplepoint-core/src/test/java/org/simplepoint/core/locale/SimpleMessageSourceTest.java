package org.simplepoint.core.locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.exception.NotImplementedException;
import org.springframework.context.MessageSourceResolvable;

class SimpleMessageSourceTest {

  private CacheSimpleMessageSource cacheSource;
  private SimpleMessageSource messageSource;

  @BeforeEach
  void setUp() {
    cacheSource = mock(CacheSimpleMessageSource.class);
    messageSource = new SimpleMessageSource(cacheSource);
  }

  // -------- getMessage(code, args, defaultMessage, locale) --------

  @Test
  void getMessage_withDefault_messageFound_returnsFormattedMessage() {
    when(cacheSource.getMessage("welcome", "en_US")).thenReturn("Hello {0}!");
    String result = messageSource.getMessage("welcome", new Object[]{"Alice"}, "default", Locale.US);
    assertThat(result).isEqualTo("Hello Alice!");
  }

  @Test
  void getMessage_withDefault_messageNotFound_usesDefaultMessage() {
    when(cacheSource.getMessage("missing", "en_US")).thenReturn(null);
    String result = messageSource.getMessage("missing", new Object[]{"Alice"}, "Default {0}", Locale.US);
    assertThat(result).isEqualTo("Default Alice");
  }

  @Test
  void getMessage_withDefault_messageNotFound_defaultNull_returnsCode() {
    when(cacheSource.getMessage("missing", "en_US")).thenReturn(null);
    String result = messageSource.getMessage("missing", new Object[]{}, null, Locale.US);
    assertThat(result).isEqualTo("missing");
  }

  @Test
  void getMessage_withDefault_messageNotFound_defaultEmpty_returnsCode() {
    when(cacheSource.getMessage("missing", "en_US")).thenReturn("");
    String result = messageSource.getMessage("missing", new Object[]{}, "", Locale.US);
    assertThat(result).isEqualTo("missing");
  }

  @Test
  void getMessage_withDefault_messageFoundButEmpty_usesDefault() {
    when(cacheSource.getMessage("key", "en_US")).thenReturn("");
    String result = messageSource.getMessage("key", new Object[]{}, "fallback", Locale.US);
    assertThat(result).isEqualTo("fallback");
  }

  // -------- getMessage(code, args, locale) --------

  @Test
  void getMessage_noDefault_messageFound_returnsFormattedMessage() {
    when(cacheSource.getMessage("greet", "en_US")).thenReturn("Hi {0}!");
    String result = messageSource.getMessage("greet", new Object[]{"Bob"}, Locale.US);
    assertThat(result).isEqualTo("Hi Bob!");
  }

  @Test
  void getMessage_noDefault_messageNotFound_returnsCode() {
    when(cacheSource.getMessage("unknown", "en_US")).thenReturn(null);
    String result = messageSource.getMessage("unknown", new Object[]{}, Locale.US);
    assertThat(result).isEqualTo("unknown");
  }

  @Test
  void getMessage_noDefault_messageEmpty_returnsCode() {
    when(cacheSource.getMessage("empty.key", "zh_CN")).thenReturn("");
    String result = messageSource.getMessage("empty.key", new Object[]{}, Locale.CHINA);
    assertThat(result).isEqualTo("empty.key");
  }

  // -------- getMessage(resolvable, locale) --------

  @Test
  void getMessage_resolvable_throwsNotImplementedException() {
    MessageSourceResolvable resolvable = mock(MessageSourceResolvable.class);
    assertThatThrownBy(() -> messageSource.getMessage(resolvable, Locale.US))
        .isInstanceOf(NotImplementedException.class);
  }

  // -------- cache delegation --------

  @Test
  void getMessage_delegatesToCacheSource() {
    when(cacheSource.getMessage("key", "en_US")).thenReturn("value");
    messageSource.getMessage("key", new Object[]{}, Locale.US);
    verify(cacheSource).getMessage("key", "en_US");
  }
}
