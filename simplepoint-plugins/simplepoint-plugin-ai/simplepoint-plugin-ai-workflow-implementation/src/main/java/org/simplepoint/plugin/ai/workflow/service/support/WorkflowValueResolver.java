package org.simplepoint.plugin.ai.workflow.service.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves bounded Workflow references without expressions or executable code.
 */
@Component
public class WorkflowValueResolver {

  private static final int MAXIMUM_DEPTH = 32;

  private static final Pattern PATH_SEGMENT =
      Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

  /**
   * Resolves a template against immutable input and node outputs.
   */
  public Object resolve(
      final Object template,
      final Map<String, Object> input,
      final Map<String, Object> nodeOutputs
  ) {
    return resolveNode(template, input, nodeOutputs, 0);
  }

  private Object resolveNode(
      final Object value,
      final Map<String, Object> input,
      final Map<String, Object> nodeOutputs,
      final int depth
  ) {
    assertDepth(depth);
    if (value instanceof Map<?, ?> map) {
      if (map.containsKey("$ref")) {
        if (map.size() != 1
            || !(map.get("$ref") instanceof String reference)) {
          throw new IllegalArgumentException(
              "Workflow $ref must be one string field"
          );
        }
        return resolveReference(reference, input, nodeOutputs);
      }
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach((key, nested) -> result.put(
          String.valueOf(key),
          resolveNode(nested, input, nodeOutputs, depth + 1)
      ));
      return result;
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>(list.size());
      list.forEach(item -> result.add(
          resolveNode(item, input, nodeOutputs, depth + 1)
      ));
      return result;
    }
    return value;
  }

  private static Object resolveReference(
      final String reference,
      final Map<String, Object> input,
      final Map<String, Object> nodeOutputs
  ) {
    List<String> parts = parts(reference);
    Object current;
    int offset;
    if ("input".equals(parts.get(0))) {
      current = input;
      offset = 1;
    } else if ("nodes".equals(parts.get(0)) && parts.size() >= 2) {
      current = nodeOutputs.get(parts.get(1));
      if (current == null) {
        throw new IllegalArgumentException(
            "Workflow node output is unavailable: " + reference
        );
      }
      offset = parts.size() >= 3
          && "output".equals(parts.get(2)) ? 3 : 2;
    } else {
      throw new IllegalArgumentException(
          "Workflow reference must start with input or nodes"
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
      throw new IllegalArgumentException(
          "Workflow reference is invalid"
      );
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
