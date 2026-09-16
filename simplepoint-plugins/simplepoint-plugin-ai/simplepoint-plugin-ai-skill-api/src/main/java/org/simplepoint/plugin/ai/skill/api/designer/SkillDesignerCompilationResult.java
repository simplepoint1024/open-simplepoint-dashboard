package org.simplepoint.plugin.ai.skill.api.designer;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of compiling one Skill Designer Document.
 *
 * @param valid whether compilation completed without error diagnostics
 * @param manifest canonical Skill Manifest when compilation succeeded
 * @param contentHash canonical Manifest content hash when available
 * @param diagnostics complete ordered diagnostics
 */
@Schema(title = "Skill Designer Compilation Result")
public record SkillDesignerCompilationResult(
    boolean valid,
    Map<String, Object> manifest,
    String contentHash,
    List<SkillDesignerDiagnostic> diagnostics
) {

  /** Creates a defensively copied compilation result. */
  public SkillDesignerCompilationResult {
    if (manifest != null) {
      manifest = Collections.unmodifiableMap(new LinkedHashMap<>(manifest));
    }
    diagnostics = diagnostics == null
        ? List.of()
        : Collections.unmodifiableList(new ArrayList<>(diagnostics));
  }
}
