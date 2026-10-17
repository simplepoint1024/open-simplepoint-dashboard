package org.simplepoint.plugin.app.form.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField;

/** Base metadata is projected separately from designer-controlled business values. */
final class FormRecordSystemFields {
  private FormRecordSystemFields() {}

  static Map<String, Object> fromStorage(Map<String, Object> values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var field : RecordSystemField.values()) {
      if (values.containsKey(field.storageCode())) {
        result.put(field.property(), values.get(field.storageCode()));
      }
    }
    return result;
  }

  static Map<String, Object> project(Map<String, Object> captured, String model, Map<String, String> permissions) {
    Map<String, Object> result = new LinkedHashMap<>();
    String prefix = RecordPermissionModel.key(model) + "#";
    for (var field : RecordSystemField.values()) {
      String policy = permissions.get(prefix + field.storageCode());
      if (!captured.containsKey(field.property()) || "HIDDEN".equals(policy)) {
        continue;
      }
      Object value = captured.get(field.property());
      result.put(field.property(), "MASKED".equals(policy) && value != null ? "***" : value);
    }
    return result;
  }
}
