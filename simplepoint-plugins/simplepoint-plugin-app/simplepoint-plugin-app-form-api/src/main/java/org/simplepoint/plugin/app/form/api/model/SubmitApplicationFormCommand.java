package org.simplepoint.plugin.app.form.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User intent only; physical schemas, mappings, actor authority and process business key are
 * server-owned.
 */
public record SubmitApplicationFormCommand(
    ApplicationFormRuntimeIdentity runtime,
    String idempotencyKey,
    String recordId,
    Long expectedVersion,
    String definitionId,
    int definitionVersion,
    String previousSubmissionId,
    String title,
    Map<String, Object> values,
    Map<String, Object> variables,
    List<String> ccUserIds) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public SubmitApplicationFormCommand {
    if (runtime == null
        || idempotencyKey == null
        || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        || definitionId == null
        || definitionId.isBlank()
        || definitionId.length() > 64
        || definitionVersion < 1
        || (recordId == null) != (expectedVersion == null)
        || recordId != null && (recordId.isBlank() || recordId.length() > 64 || expectedVersion < 0)
        || previousSubmissionId != null
            && !previousSubmissionId.matches("[a-f0-9]{64}")
        || title == null
        || title.isBlank()
        || title.length() > 240) {
      throw new IllegalArgumentException("Invalid form workflow submission intent");
    }
    values = immutable(values);
    variables = immutable(variables);
    ccUserIds = ccUserIds == null ? List.of() : List.copyOf(ccUserIds);
    if (variables.size() > 100 || ccUserIds.size() > 50) {
      throw new IllegalArgumentException("Submission budget exceeded");
    }
  }

  private static Map<String, Object> immutable(Map<String, Object> values) {
    return values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
