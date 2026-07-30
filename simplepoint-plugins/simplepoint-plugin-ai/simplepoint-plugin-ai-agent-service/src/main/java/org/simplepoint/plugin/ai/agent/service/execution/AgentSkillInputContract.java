package org.simplepoint.plugin.ai.agent.service.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime Skill input contract enriched with constraints implied by its
 * immutable declarative workflow.
 */
final class AgentSkillInputContract {

  private static final Pattern TEMPLATE_VARIABLE =
      Pattern.compile("\\{([^{}]+)}");

  private final Map<String, Object> schema;

  private final List<ResourceConstraint> resourceConstraints;

  private AgentSkillInputContract(
      final Map<String, Object> schema,
      final List<ResourceConstraint> resourceConstraints
  ) {
    this.schema = schema;
    this.resourceConstraints = resourceConstraints;
  }

  static AgentSkillInputContract create(
      final Map<String, Object> inputSchema,
      final Map<String, Object> manifest
  ) {
    Map<String, Object> schema = mutableMap(inputSchema);
    Map<String, String> templates = resourceTemplates(manifest);
    List<ResourceConstraint> constraints = new ArrayList<>();
    Map<String, Object> spec = map(manifest.get("spec"));
    Map<String, Object> workflow = map(spec.get("workflow"));
    collectConstraints(
        workflow.get("steps"),
        templates,
        constraints
    );
    constraints.forEach(constraint -> enrichSchema(schema, constraint));
    return new AgentSkillInputContract(
        schema,
        List.copyOf(constraints)
    );
  }

  Map<String, Object> schema() {
    return schema;
  }

  void validate(final Map<String, Object> input) {
    for (ResourceConstraint constraint : resourceConstraints) {
      Object value = valueAt(input, constraint.inputPath());
      if (!(value instanceof String uri)
          || !matchesTemplate(constraint.uriTemplate(), uri)) {
        throw new IllegalArgumentException(
            "Skill argument " + String.join(".", constraint.inputPath())
                + " must match pinned MCP resource URI template "
                + constraint.uriTemplate()
        );
      }
    }
  }

  private static Map<String, String> resourceTemplates(
      final Map<String, Object> manifest
  ) {
    Map<String, String> result = new LinkedHashMap<>();
    Map<String, Object> spec = map(manifest.get("spec"));
    Object resources = spec.get("resources");
    if (!(resources instanceof List<?> list)) {
      return result;
    }
    for (Object item : list) {
      Map<String, Object> resource = map(item);
      String alias = text(resource.get("alias"));
      String template = text(resource.get("uriTemplate"));
      if (alias != null && template != null) {
        result.put(alias, template);
      }
    }
    return result;
  }

  private static void collectConstraints(
      final Object value,
      final Map<String, String> templates,
      final List<ResourceConstraint> constraints
  ) {
    if (value instanceof List<?> list) {
      list.forEach(item -> collectConstraints(item, templates, constraints));
      return;
    }
    if (!(value instanceof Map<?, ?> source)) {
      return;
    }
    Map<String, Object> node = map(source);
    if ("resource".equals(text(node.get("type")))) {
      String alias = text(node.get("resource"));
      String template = templates.get(alias);
      List<String> inputPath = inputPath(node.get("uri"));
      if (template != null && !inputPath.isEmpty()) {
        ResourceConstraint constraint =
            new ResourceConstraint(inputPath, template);
        if (!constraints.contains(constraint)) {
          constraints.add(constraint);
        }
      }
    }
    node.values().forEach(
        child -> collectConstraints(child, templates, constraints)
    );
  }

  private static List<String> inputPath(final Object value) {
    Map<String, Object> reference = map(value);
    if (reference.size() != 1) {
      return List.of();
    }
    String path = text(reference.get("$ref"));
    if (path == null || !path.startsWith("input.")) {
      return List.of();
    }
    String[] segments = path.substring("input.".length()).split("\\.");
    List<String> result = new ArrayList<>();
    for (String segment : segments) {
      if (segment.isBlank()) {
        return List.of();
      }
      result.add(segment);
    }
    return List.copyOf(result);
  }

  private static void enrichSchema(
      final Map<String, Object> schema,
      final ResourceConstraint constraint
  ) {
    Map<String, Object> node = schema;
    for (String segment : constraint.inputPath()) {
      Object propertiesValue = node.get("properties");
      if (!(propertiesValue instanceof Map<?, ?> properties)) {
        return;
      }
      Object child = properties.get(segment);
      if (!(child instanceof Map<?, ?> childSchema)) {
        return;
      }
      node = mutableMap(childSchema);
      @SuppressWarnings("unchecked")
      Map<String, Object> mutableProperties =
          (Map<String, Object>) propertiesValue;
      mutableProperties.put(segment, node);
    }
    String requirement = "Must match the pinned MCP resource URI template "
        + constraint.uriTemplate() + ".";
    String current = text(node.get("description"));
    node.put(
        "description",
        current == null ? requirement : current + " " + requirement
    );
    if (!node.containsKey("examples")) {
      node.put("examples", List.of(exampleUri(constraint.uriTemplate())));
    }
  }

  private static Object valueAt(
      final Map<String, Object> input,
      final List<String> path
  ) {
    Object current = input;
    for (String segment : path) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(segment);
    }
    return current;
  }

  private static boolean matchesTemplate(
      final String template,
      final String resourceUri
  ) {
    if (template == null || template.isBlank()
        || resourceUri == null || resourceUri.isBlank()) {
      return false;
    }
    StringBuilder expression = new StringBuilder("^");
    int cursor = 0;
    Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
    while (matcher.find()) {
      expression.append(Pattern.quote(
          template.substring(cursor, matcher.start())
      ));
      String variable = matcher.group(1);
      boolean reservedExpansion = variable.startsWith("+");
      String name = reservedExpansion ? variable.substring(1) : variable;
      if (!name.matches("[A-Za-z0-9_.-]+")) {
        return false;
      }
      expression.append(reservedExpansion ? ".+" : "[^/?#]+");
      cursor = matcher.end();
    }
    expression.append(Pattern.quote(template.substring(cursor))).append("$");
    return Pattern.compile(expression.toString())
        .matcher(resourceUri)
        .matches();
  }

  private static String exampleUri(final String template) {
    StringBuilder example = new StringBuilder();
    int cursor = 0;
    Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
    while (matcher.find()) {
      example.append(template, cursor, matcher.start());
      String variable = matcher.group(1);
      example.append(variable.startsWith("+")
          ? variable.substring(1) : variable);
      cursor = matcher.end();
    }
    example.append(template.substring(cursor));
    return example.toString();
  }

  private static Map<String, Object> mutableMap(final Object value) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (value instanceof Map<?, ?> source) {
      source.forEach((key, child) ->
          result.put(String.valueOf(key), mutableValue(child)));
    }
    return result;
  }

  private static Object mutableValue(final Object value) {
    if (value instanceof Map<?, ?> map) {
      return mutableMap(map);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(AgentSkillInputContract::mutableValue).toList();
    }
    return value;
  }

  private static Map<String, Object> map(final Object value) {
    if (!(value instanceof Map<?, ?> source)) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, child) -> result.put(String.valueOf(key), child));
    return result;
  }

  private static String text(final Object value) {
    if (!(value instanceof String text) || text.isBlank()) {
      return null;
    }
    return text;
  }

  private record ResourceConstraint(
      List<String> inputPath,
      String uriTemplate
  ) {
  }
}
