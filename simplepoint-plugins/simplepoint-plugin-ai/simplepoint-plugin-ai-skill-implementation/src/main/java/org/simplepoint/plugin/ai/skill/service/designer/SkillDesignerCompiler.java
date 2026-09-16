package org.simplepoint.plugin.ai.skill.service.designer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerCompilationResult;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDiagnostic;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDiagnostic.Severity;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.WorkflowBindings;
import org.springframework.stereotype.Component;

/**
 * Authoritative structural compiler for Skill Designer Documents.
 *
 * <p>Capability snapshot resolution and publication admission remain part of
 * the Skill version service. This compiler guarantees that a Draft can be
 * represented by the current immutable Manifest and runtime workflow grammar.
 */
@Component
public class SkillDesignerCompiler {

  private final SkillDesignerManifestAdapter manifestAdapter;

  private final SkillWorkflowPlanCompiler workflowCompiler;

  private final SkillDesignerValidator validator;

  private final ObjectMapper canonicalMapper;

  /** Creates the Skill designer compiler. */
  public SkillDesignerCompiler(
      final SkillDesignerManifestAdapter manifestAdapter,
      final SkillWorkflowPlanCompiler workflowCompiler,
      final SkillDesignerValidator validator,
      final ObjectMapper objectMapper
  ) {
    this.manifestAdapter = manifestAdapter;
    this.workflowCompiler = workflowCompiler;
    this.validator = validator;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  /** Compiles a designer document into a canonical v1alpha1 Skill Manifest. */
  public SkillDesignerCompilationResult compile(
      final SkillDesignerDocument document
  ) {
    List<SkillDesignerDiagnostic> diagnostics = validator.validate(document);
    if (diagnostics.stream().anyMatch(
        diagnostic -> diagnostic.severity() == Severity.ERROR)) {
      return new SkillDesignerCompilationResult(
          false,
          null,
          null,
          diagnostics
      );
    }
    try {
      Map<String, Object> manifest = manifestAdapter.toManifest(document);
      Map<String, Object> spec = map(manifest.get("spec"), "Skill spec");
      workflowCompiler.compile(
          map(spec.get("workflow"), "Skill workflow"),
          new WorkflowBindings(
              aliases(spec.get("tools"), "Skill tools"),
              aliases(spec.get("prompts"), "Skill prompts"),
              aliases(spec.get("resources"), "Skill resources")
          )
      );
      String canonicalJson = canonicalJson(manifest);
      return new SkillDesignerCompilationResult(
          true,
          manifest,
          sha256(canonicalJson),
          diagnostics
      );
    } catch (IllegalArgumentException exception) {
      List<SkillDesignerDiagnostic> failed = new ArrayList<>(diagnostics);
      failed.add(new SkillDesignerDiagnostic(
          Severity.ERROR,
          diagnosticCode(exception.getMessage()),
          safeMessage(exception),
          null,
          null,
          null,
          Map.of("exceptionType", exception.getClass().getSimpleName())
      ));
      return new SkillDesignerCompilationResult(
          false,
          null,
          null,
          failed
      );
    }
  }

  private Set<String> aliases(final Object value, final String label) {
    if (value == null) {
      return Set.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(label + " must be an array");
    }
    Set<String> result = new HashSet<>();
    for (Object item : list) {
      Map<String, Object> binding = map(item, label + " item");
      Object aliasValue = binding.get("alias");
      if (!(aliasValue instanceof String alias) || alias.isBlank()) {
        throw new IllegalArgumentException(label + " alias is required");
      }
      if (!result.add(alias)) {
        throw new IllegalArgumentException(
            label + " contains duplicate alias " + alias
        );
      }
    }
    return Set.copyOf(result);
  }

  private Map<String, Object> map(final Object value, final String label) {
    if (!(value instanceof Map<?, ?> source)) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> result = canonicalMapper.convertValue(
          source,
          Map.class
      );
      return result;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(label + " is invalid", exception);
    }
  }

  private String canonicalJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Skill Manifest is not serializable",
          exception
      );
    }
  }

  private static String sha256(final String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static String diagnosticCode(final String message) {
    String normalized = message == null ? "" : message.toLowerCase();
    if (normalized.contains("edge") || normalized.contains("node")) {
      return "SKILL_DESIGNER_GRAPH_INVALID";
    }
    if (normalized.contains("schema")) {
      return "SKILL_DESIGNER_SCHEMA_INVALID";
    }
    if (normalized.contains("workflow")
        || normalized.contains("step")
        || normalized.contains("branch")) {
      return "SKILL_DESIGNER_WORKFLOW_INVALID";
    }
    return "SKILL_DESIGNER_DOCUMENT_INVALID";
  }

  private static String safeMessage(final IllegalArgumentException exception) {
    return exception.getMessage() == null || exception.getMessage().isBlank()
        ? "Skill Designer Document compilation failed"
        : exception.getMessage();
  }
}
