package org.simplepoint.common.server.i18n;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommonI18nMessagesTest {

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
  void commonMessagesIncludeAuthorizationScopeLabelsForSupportedLocales() throws Exception {
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
    String path = "META-INF/simplepoint/i18n/" + locale + "/common.json";
    try (InputStream input = CommonI18nMessagesTest.class.getClassLoader().getResourceAsStream(path)) {
      assertTrue(input != null, () -> "missing " + path);
      return OBJECT_MAPPER.readValue(input, new TypeReference<>() {
      });
    }
  }
}
