package org.simplepoint.plugin.app.form.service;

import java.util.Map;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

final class FormCollectionPermissions {
  private FormCollectionPermissions() {}

  static boolean readable(
      PublishedApplicationForm source, String relationId, Map<String, String> fields) {
    String access = access(source, relationId, fields);
    return access == null || "EDITABLE".equals(access) || "VISIBLE".equals(access);
  }

  static boolean writable(
      PublishedApplicationForm source, String relationId, Map<String, String> fields) {
    String access = access(source, relationId, fields);
    return access == null || "EDITABLE".equals(access);
  }

  private static String access(
      PublishedApplicationForm source, String relationId, Map<String, String> fields) {
    return fields.get(
        RecordPermissionModel.key(source.physicalModelId())
            + "#"
            + RecordPermissionModel.collectionField(relationId));
  }
}
