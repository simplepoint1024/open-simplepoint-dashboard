package org.simplepoint.plugin.i18n.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.entity.Message;

class I18nMessageServiceImplTest {

  @Test
  void collapsesIdenticalKeysFromMultipleNamespaces() {
    assertEquals(Map.of("ai.common.enabled", "Enabled"),
        I18nMessageServiceImpl.mergeMapping(List.of(
        message("ai.common.enabled", "Enabled"),
        message("ai.common.enabled", "Enabled")
    )));
  }

  @Test
  void rejectsConflictingValuesForTheSameKey() {
    IllegalStateException exception = assertThrows(IllegalStateException.class,
        () -> I18nMessageServiceImpl.mergeMapping(List.of(
        message("ai.common.disabled", "Disabled"),
        message("ai.common.disabled", "Not enabled")
    )));
    assertTrue(exception.getMessage().contains("ai.common.disabled"));
  }

  private static Message message(final String code, final String value) {
    Message message = new Message();
    message.setCode(code);
    message.setMessage(value);
    return message;
  }
}
