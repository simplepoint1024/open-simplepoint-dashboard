package org.simplepoint.plugin.i18n.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class I18nCommonMessagesTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String[] SCOPE_KEYS = {
    "scope.platform",
    "scope.personal",
    "scope.tenantOwner",
    "scope.tenantAdmin",
    "scope.tenant",
    "scope.unknown"
  };

  @Test
  void commonMessages_includeAuthorizationScopeLabelsForSupportedLocales() throws Exception {
    assertScopeMessages("zh-CN");
    assertScopeMessages("en-US");
  }

  private static void assertScopeMessages(String locale) throws Exception {
    Map<String, String> messages = readCommonMessages(locale);
    for (String key : SCOPE_KEYS) {
      assertTrue(messages.containsKey(key), () -> locale + " missing " + key);
      assertFalse(messages.get(key).isBlank(), () -> locale + " blank " + key);
    }
  }

  private static Map<String, String> readCommonMessages(String locale) throws Exception {
    String path = "i18n/messages/" + locale + "/common.json";
    try (InputStream input = I18nCommonMessagesTest.class.getClassLoader().getResourceAsStream(path)) {
      assertTrue(input != null, () -> "missing " + path);
      return OBJECT_MAPPER.readValue(input, new TypeReference<>() {});
    }
  }
}
