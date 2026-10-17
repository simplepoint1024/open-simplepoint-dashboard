package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict attachment references; the client cannot supply a download URL or executable content. */
final class FormAttachmentValues {
  private static final ObjectMapper JSON = new ObjectMapper();

  private FormAttachmentValues() {}

  static List<Attachment> parse(Object value) {
    if (value == null || "".equals(value)) {
      return List.of();
    }
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("Expected attachment JSON text");
    }
    try {
      var node = JSON.readTree(text);
      if (!node.isArray() || node.size() > 10) {
        throw new IllegalArgumentException("Invalid attachment count");
      }
      Set<String> ids = new HashSet<>();
      var result = new java.util.ArrayList<Attachment>();
      for (var file : node) {
        if (!file.isObject() || file.size() != 3 || !file.path("id").isTextual()
            || !file.path("id").asText().matches("[A-Za-z0-9_-]{1,64}")
            || !ids.add(file.path("id").asText()) || !file.path("name").isTextual()
            || file.path("name").asText().isBlank() || file.path("name").asText().length() > 255
            || !file.path("size").isIntegralNumber() || !file.path("size").canConvertToLong()
            || file.path("size").asLong() <= 0 || file.path("size").asLong() > 10 * 1024 * 1024) {
          throw new IllegalArgumentException("Invalid attachment metadata");
        }
        result.add(new Attachment(file.path("id").asText(), file.path("name").asText(), file.path("size").asLong()));
      }
      return List.copyOf(result);
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException("Invalid attachment JSON", failure);
    }
  }

  record Attachment(String id, String name, long size) {}
}
