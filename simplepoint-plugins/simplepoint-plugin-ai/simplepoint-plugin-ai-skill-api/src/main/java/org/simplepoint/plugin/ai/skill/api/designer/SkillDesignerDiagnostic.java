package org.simplepoint.plugin.ai.skill.api.designer;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured validation or compilation diagnostic for a Skill Draft.
 *
 * @param severity diagnostic severity
 * @param code stable machine-readable code
 * @param message localized or fallback human-readable message
 * @param nodeId optional designer node ID
 * @param fieldPath optional node property path
 * @param jsonPointer optional RFC 6901 document location
 * @param details optional structured diagnostic details
 */
@Schema(title = "Skill Designer Diagnostic")
public record SkillDesignerDiagnostic(
    Severity severity,
    String code,
    String message,
    String nodeId,
    String fieldPath,
    String jsonPointer,
    Map<String, Object> details
) {

  /** Creates a defensively copied diagnostic. */
  public SkillDesignerDiagnostic {
    if (details != null) {
      details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
  }

  /** Diagnostic severity. */
  public enum Severity {
    ERROR,
    WARNING,
    INFO
  }
}
