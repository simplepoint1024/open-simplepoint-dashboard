package org.simplepoint.security.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ResourceDeclarationDocumentTest {
  @Test
  void ownerAndNestedCodesAreValidatedByOneParserForRegistrationAndRequestScopes()
      throws Exception {
    var document =
        read(
            "{\"module\":\"historic\",\"resources\":[{\"code\":\"page\",\"children\":[{\"code\":\"action\",\"pluginId\":\"historic\"}]}]}");
    assertEquals(
        "action", document.resources().get(0).path("children").get(0).path("code").asText());
    for (String invalid :
        new String[] {
          "{\"module\":\"adapter\",\"resources\":[]}",
          "{\"resources\":[{\"code\":\"page\",\"pluginId\":\"other\"}]}",
          "{\"resources\":[{\"code\":\"page\",\"children\":[{\"code\":\"page\"}]}]}",
          "{\"resources\":[{\"code\":\"page\",\"children\":{}}]}",
          "{\"resources\":[],\"resources\":[]}",
          "{\"resources\":[]} {}",
          "[]",
          "null"
        }) {
      assertThrows(Exception.class, () -> read(invalid));
    }
  }

  @Test
  void oversizedDeepAndExcessivelyWideDeclarationsAreBounded() {
    assertThrows(
        Exception.class,
        () ->
            ResourceDeclarationDocument.read("adapter", "historic", new byte[2 * 1024 * 1024 + 1]));
    assertThrows(
        Exception.class,
        () -> read("{\"resources\":" + "[".repeat(65) + "{}" + "]".repeat(65) + "}"));
    var wide = new StringBuilder("{\"resources\":[");
    for (int i = 0; i <= 10_000; i++) {
      if (i > 0) {
        wide.append(',');
      }
      wide.append("{\"code\":\"r").append(i).append("\"}");
    }
    wide.append("]}");
    assertThrows(Exception.class, () -> read(wide.toString()));
  }

  private static ResourceDeclarationDocument read(String json) throws Exception {
    return ResourceDeclarationDocument.read(
        "adapter", "historic", json.getBytes(StandardCharsets.UTF_8));
  }
}
