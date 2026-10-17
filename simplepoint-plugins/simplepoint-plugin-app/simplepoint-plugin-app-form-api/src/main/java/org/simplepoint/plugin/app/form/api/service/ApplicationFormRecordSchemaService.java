package org.simplepoint.plugin.app.form.api.service;

import java.util.Map;

/** Builds the platform SimpleTable contract for one authorized published form page. */
public interface ApplicationFormRecordSchemaService {

  /** Returns a field-policy and action-policy filtered table schema. */
  Map<String, Object> schema(String applicationId, String pageId);
}
