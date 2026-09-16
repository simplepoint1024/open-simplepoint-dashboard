package org.simplepoint.plugin.ai.skill.service.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves safe declarative references without evaluating code or expressions.
 */
@Component
public class SkillWorkflowTemplateResolver {

  private static final int MAXIMUM_DEPTH = 32;

  private static final Pattern PATH_SEGMENT =
      Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

  /**
   * Validates all declarative references in a template.
   */
  public void validateTemplate(
      final Object template,
      final List<String> availableStepIds,
      final String label
  ) {
    validateNode(template, availableStepIds, label, 0);
  }

  /**
   * Resolves a template against immutable workflow input and prior results.
   */
  public Object resolve(
      final Object template,
      final Map<String, Object> input,
      final Map<String, Object> stepResults
  ) {
    return resolveNode(template, input, stepResults, 0);
  }

  private void validateNode(
      final Object value,
      final List<String> availableStepIds,
      final String label,
      final int depth
  ) {
    assertDepth(depth);
    if (value instanceof Map<?, ?> map) {
      if (map.containsKey("$ref")) {
        if (map.size() != 1 || !(map.get("$ref") instanceof String reference)) {
          throw new IllegalArgumentException(
              label + " $ref object must contain exactly one string field"
          );
        }
        validateReference(reference, availableStepIds, label);
        return;
      }
      map.forEach((key, nested) -> validateNode(
          nested,
          availableStepIds,
          label + "." + key,
          depth + 1
      ));
    } else if (value instanceof List<?> list) {
      for (int index = 0; index < list.size(); index++) {
        validateNode(
            list.get(index),
            availableStepIds,
            label + "[" + index + "]",
            depth + 1
        );
      }
    }
  }

  private Object resolveNode(
      final Object value,
      final Map<String, Object> input,
      final Map<String, Object> stepResults,
      final int depth
  ) {
    assertDepth(depth);
    if (value instanceof Map<?, ?> map) {
      if (map.containsKey("$ref")) {
        if (map.size() != 1 || !(map.get("$ref") instanceof String reference)) {
          throw new IllegalArgumentException(
              "Workflow $ref object must contain exactly one string field"
          );
        }
        return resolveReference(reference, input, stepResults);
      }
      Map<String, Object> resolved = new LinkedHashMap<>();
      map.forEach((key, nested) -> resolved.put(
          String.valueOf(key),
          resolveNode(nested, input, stepResults, depth + 1)
      ));
      return resolved;
    }
    if (value instanceof List<?> list) {
      List<Object> resolved = new ArrayList<>(list.size());
      list.forEach(item ->
          resolved.add(resolveNode(item, input, stepResults, depth + 1)));
      return resolved;
    }
    return value;
  }

  private static void validateReference(
      final String reference,
      final List<String> availableStepIds,
      final String label
  ) {
    List<String> parts = parts(reference);
    if ("input".equals(parts.get(0))) {
      return;
    }
    if (!"steps".equals(parts.get(0)) || parts.size() < 2
        || !availableStepIds.contains(parts.get(1))) {
      throw new IllegalArgumentException(
          label + " references unavailable workflow data: " + reference
      );
    }
  }

  private static Object resolveReference(
      final String reference,
      final Map<String, Object> input,
      final Map<String, Object> stepResults
  ) {
    List<String> parts = parts(reference);
    Object current;
    int offset;
    if ("input".equals(parts.get(0))) {
      current = input;
      offset = 1;
    } else if ("steps".equals(parts.get(0)) && parts.size() >= 2) {
      current = stepResults.get(parts.get(1));
      if (current == null) {
        throw new IllegalArgumentException(
            "Workflow reference is unavailable: " + reference
        );
      }
      offset = 2;
    } else {
      throw new IllegalArgumentException(
          "Workflow reference must start with input or steps"
      );
    }
    for (int index = offset; index < parts.size(); index++) {
      if (!(current instanceof Map<?, ?> map)
          || !map.containsKey(parts.get(index))) {
        throw new IllegalArgumentException(
            "Workflow reference does not exist: " + reference
        );
      }
      current = map.get(parts.get(index));
    }
    return current;
  }

  private static List<String> parts(final String reference) {
    if (reference == null || reference.isBlank()
        || reference.length() > 512) {
      throw new IllegalArgumentException("Workflow reference is invalid");
    }
    String[] raw = reference.trim().split("\\.", -1);
    List<String> result = new ArrayList<>(raw.length);
    for (String part : raw) {
      if (!PATH_SEGMENT.matcher(part).matches()) {
        throw new IllegalArgumentException(
            "Workflow reference contains an invalid path segment"
        );
      }
      result.add(part);
    }
    return List.copyOf(result);
  }

  private static void assertDepth(final int depth) {
    if (depth > MAXIMUM_DEPTH) {
      throw new IllegalArgumentException(
          "Workflow template exceeds maximum JSON depth"
      );
    }
  }
}
