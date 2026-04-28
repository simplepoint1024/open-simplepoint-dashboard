package org.simplepoint.core.locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CacheSimpleMessageSourceTest {

  private I18nMessageService messageService;
  private CacheSimpleMessageSource cacheSource;

  @BeforeEach
  void setUp() {
    messageService = mock(I18nMessageService.class);
    cacheSource = new CacheSimpleMessageSource(messageService);
  }

  @Test
  void getMessage_delegatesToMessageService() {
    when(messageService.getMessage("key", "en_US")).thenReturn("Hello");
    String result = cacheSource.getMessage("key", "en_US");
    assertThat(result).isEqualTo("Hello");
    verify(messageService).getMessage("key", "en_US");
  }

  @Test
  void getMessage_returnsNullWhenServiceReturnsNull() {
    when(messageService.getMessage("missing", "en_US")).thenReturn(null);
    String result = cacheSource.getMessage("missing", "en_US");
    assertThat(result).isNull();
  }

  @Test
  void evictMessage_doesNotThrow() {
    // @CacheEvict is a no-op without a cache manager; verify method executes cleanly
    cacheSource.evictMessage("key", "en_US");
  }
}
