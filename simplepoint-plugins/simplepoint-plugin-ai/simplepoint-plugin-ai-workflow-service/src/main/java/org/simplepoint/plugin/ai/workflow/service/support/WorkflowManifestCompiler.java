package org.simplepoint.plugin.ai.workflow.service.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowDependencyType;
import org.springframework.stereotype.Component;

/**
 * Compiles a bounded declarative Agent Workflow DAG.
 */
@Component
public class WorkflowManifestCompiler {

  private static final int MAXIMUM_NODES = 128;

  private static final int MAXIMUM_EDGES = 512;

  private static final int MAXIMUM_DURATION_SECONDS = 604800;

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private static final Set<String> FORBIDDEN_KEYS = Set.of(
      "script",
      "command",
      "container",
      "entrypoint",
      "executable",
      "sourcecode"
  );

  /**
   * Validates and topologically compiles one immutable manifest.
   */
  public CompiledWorkflow compile(final Map<String, Object> manifest) {
    if (manifest == null) {
      throw new IllegalArgumentException(
          "Workflow manifest must not be null"
      );
    }
    rejectExecutableFields(manifest, "Workflow manifest");
    assertAllowedKeys(
        manifest,
        Set.of("apiVersion", "kind", "metadata", "spec"),
        "Workflow manifest"
    );
    Map<String, Object> spec = requiredMap(
        manifest.get("spec"),
        "Workflow spec"
    );
    assertAllowedKeys(
        spec,
        Set.of(
            "budgets",
            "edges",
            "failurePolicy",
            "inputSchema",
            "nodes",
            "output",
            "outputSchema"
        ),
        "Workflow spec"
    );
    List<Map<String, Object>> sourceNodes = requiredMapList(
        spec.get("nodes"),
        "Workflow nodes"
    );
    if (sourceNodes.isEmpty() || sourceNodes.size() > MAXIMUM_NODES) {
      throw new IllegalArgumentException(
          "Workflow must contain between 1 and "
              + MAXIMUM_NODES + " nodes"
      );
    }
    Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
    List<DependencyReference> dependencies = new ArrayList<>();
    for (int index = 0; index < sourceNodes.size(); index++) {
      WorkflowNode node = compileNode(
          sourceNodes.get(index),
          index,
          dependencies
      );
      if (nodes.putIfAbsent(node.id(), node) != null) {
        throw new IllegalArgumentException(
            "Duplicate Workflow node ID: " + node.id()
        );
      }
    }
    List<WorkflowEdge> edges = compileEdges(spec.get("edges"), nodes.keySet());
    List<String> topologicalOrder = topologicalOrder(nodes.keySet(), edges);
    validateReferences(
        nodes,
        edges,
        topologicalOrder,
        spec.get("output")
    );
    WorkflowBudgets budgets = compileBudgets(spec.get("budgets"));
    FailurePolicy failurePolicy =
        compileFailurePolicy(spec.get("failurePolicy"));
    return new CompiledWorkflow(
        List.copyOf(nodes.values()),
        edges,
        topologicalOrder,
        List.copyOf(dependencies),
        budgets,
        failurePolicy,
        spec.get("output")
    );
  }

  private WorkflowNode compileNode(
      final Map<String, Object> source,
      final int index,
      final List<DependencyReference> dependencies
  ) {
    String label = "Workflow nodes[" + index + "]";
    String id = requiredIdentifier(source.get("id"), label + " ID");
    String type = requiredText(source.get("type"), label + " type")
        .toLowerCase(Locale.ROOT);
    switch (type) {
      case "agent" -> {
        assertAllowedKeys(
            source,
            Set.of(
                "agentId",
                "compensation",
                "id",
                "input",
                "name",
                "type",
                "versionId"
            ),
            label
        );
        dependencies.add(new DependencyReference(
            id,
            WorkflowDependencyType.AGENT,
            requiredIdentifier(source.get("agentId"), label + " Agent ID"),
            requiredIdentifier(
                source.get("versionId"),
                label + " Agent version ID"
            )
        ));
        compileCompensation(source.get("compensation"), id, dependencies);
      }
      case "skill" -> {
        assertAllowedKeys(
            source,
            Set.of(
                "compensation",
                "id",
                "input",
                "name",
                "skillId",
                "type",
                "versionId"
            ),
            label
        );
        dependencies.add(new DependencyReference(
            id,
            WorkflowDependencyType.SKILL,
            requiredIdentifier(source.get("skillId"), label + " Skill ID"),
            requiredIdentifier(
                source.get("versionId"),
                label + " Skill version ID"
            )
        ));
        compileCompensation(source.get("compensation"), id, dependencies);
      }
      case "human" -> compileHuman(source, label);
      case "wait" -> compileWait(source, label);
      case "condition" -> compileCondition(source, label);
      case "parallel", "end" -> assertAllowedKeys(
          source,
          Set.of("id", "input", "name", "type"),
          label
      );
      default -> throw new IllegalArgumentException(
          "Unsupported Workflow node type: " + type
      );
    }
    return new WorkflowNode(
        id,
        type,
        Collections.unmodifiableMap(new LinkedHashMap<>(source))
    );
  }

  private void compileCompensation(
      final Object value,
      final String nodeId,
      final List<DependencyReference> dependencies
  ) {
    if (value == null) {
      return;
    }
    Map<String, Object> compensation = requiredMap(
        value,
        "Workflow node " + nodeId + " compensation"
    );
    assertAllowedKeys(
        compensation,
        Set.of("input", "skillId", "versionId"),
        "Workflow node " + nodeId + " compensation"
    );
    dependencies.add(new DependencyReference(
        nodeId,
        WorkflowDependencyType.COMPENSATION_SKILL,
        requiredIdentifier(
            compensation.get("skillId"),
            "Compensation Skill ID"
        ),
        requiredIdentifier(
            compensation.get("versionId"),
            "Compensation Skill version ID"
        )
    ));
  }

  private void compileHuman(
      final Map<String, Object> source,
      final String label
  ) {
    assertAllowedKeys(
        source,
        Set.of(
            "description",
            "id",
            "input",
            "inputSchema",
            "name",
            "timeoutAction",
            "timeoutSeconds",
            "title",
            "type"
        ),
        label
    );
    requiredText(source.get("title"), label + " title");
    positiveInt(
        source.get("timeoutSeconds"),
        label + " timeoutSeconds",
        MAXIMUM_DURATION_SECONDS
    );
    String action = optionalText(
        source.get("timeoutAction"),
        "FAIL"
    ).toUpperCase(Locale.ROOT);
    if (!Set.of("FAIL", "CONTINUE", "CANCEL").contains(action)) {
      throw new IllegalArgumentException(
          label + " timeoutAction is invalid"
      );
    }
  }

  private void compileWait(
      final Map<String, Object> source,
      final String label
  ) {
    assertAllowedKeys(
        source,
        Set.of("durationSeconds", "id", "name", "type"),
        label
    );
    positiveInt(
        source.get("durationSeconds"),
        label + " durationSeconds",
        MAXIMUM_DURATION_SECONDS
    );
  }

  private void compileCondition(
      final Map<String, Object> source,
      final String label
  ) {
    assertAllowedKeys(
        source,
        Set.of("condition", "id", "name", "type"),
        label
    );
    if (source.get("condition") == null) {
      throw new IllegalArgumentException(
          label + " condition is required"
      );
    }
    validateCondition(source.get("condition"), 0, label + " condition");
  }

  private List<WorkflowEdge> compileEdges(
      final Object value,
      final Set<String> nodeIds
  ) {
    List<Map<String, Object>> source = value == null
        ? List.of() : requiredMapList(value, "Workflow edges");
    if (source.size() > MAXIMUM_EDGES) {
      throw new IllegalArgumentException(
          "Workflow has too many edges"
      );
    }
    Set<String> unique = new HashSet<>();
    List<WorkflowEdge> edges = new ArrayList<>();
    for (int index = 0; index < source.size(); index++) {
      Map<String, Object> edge = source.get(index);
      String label = "Workflow edges[" + index + "]";
      assertAllowedKeys(
          edge,
          Set.of("condition", "from", "to"),
          label
      );
      String from = requiredIdentifier(edge.get("from"), label + " from");
      String to = requiredIdentifier(edge.get("to"), label + " to");
      if (!nodeIds.contains(from) || !nodeIds.contains(to)) {
        throw new IllegalArgumentException(
            label + " references an unknown node"
        );
      }
      if (from.equals(to)) {
        throw new IllegalArgumentException(
            "Workflow edge cannot reference the same node"
        );
      }
      Object condition = edge.get("condition");
      if (condition != null) {
        validateCondition(condition, 0, label + " condition");
      }
      String key = from + "\u0000" + to;
      if (!unique.add(key)) {
        throw new IllegalArgumentException(
            "Duplicate Workflow edge: " + from + " -> " + to
        );
      }
      edges.add(new WorkflowEdge(from, to, condition));
    }
    return List.copyOf(edges);
  }

  private List<String> topologicalOrder(
      final Set<String> nodeIds,
      final List<WorkflowEdge> edges
  ) {
    Map<String, Integer> indegree = new LinkedHashMap<>();
    Map<String, List<String>> outgoing = new HashMap<>();
    nodeIds.forEach(id -> indegree.put(id, 0));
    for (WorkflowEdge edge : edges) {
      indegree.compute(edge.to(), (key, count) -> count + 1);
      outgoing.computeIfAbsent(edge.from(), key -> new ArrayList<>())
          .add(edge.to());
    }
    ArrayDeque<String> ready = new ArrayDeque<>();
    indegree.forEach((id, count) -> {
      if (count == 0) {
        ready.add(id);
      }
    });
    if (ready.isEmpty()) {
      throw new IllegalArgumentException(
          "Workflow graph must be acyclic and contain a root node"
      );
    }
    List<String> order = new ArrayList<>();
    while (!ready.isEmpty()) {
      String id = ready.removeFirst();
      order.add(id);
      for (String target : outgoing.getOrDefault(id, List.of())) {
        int remaining = indegree.compute(
            target,
            (key, count) -> count - 1
        );
        if (remaining == 0) {
          ready.addLast(target);
        }
      }
    }
    if (order.size() != nodeIds.size()) {
      throw new IllegalArgumentException(
          "Workflow graph must be acyclic"
      );
    }
    return List.copyOf(order);
  }

  private WorkflowBudgets compileBudgets(final Object value) {
    Map<String, Object> budgets = value == null
        ? Map.of() : requiredMap(value, "Workflow budgets");
    assertAllowedKeys(
        budgets,
        Set.of(
            "maximumDurationSeconds",
            "maximumNodeExecutions",
            "maximumParallelism"
        ),
        "Workflow budgets"
    );
    return new WorkflowBudgets(
        positiveInt(
            budgets.getOrDefault("maximumDurationSeconds", 86400),
            "Workflow maximumDurationSeconds",
            MAXIMUM_DURATION_SECONDS
        ),
        positiveInt(
            budgets.getOrDefault("maximumNodeExecutions", 512),
            "Workflow maximumNodeExecutions",
            4096
        ),
        positiveInt(
            budgets.getOrDefault("maximumParallelism", 16),
            "Workflow maximumParallelism",
            64
        )
    );
  }

  private FailurePolicy compileFailurePolicy(final Object value) {
    Map<String, Object> policy = value == null
        ? Map.of() : requiredMap(value, "Workflow failurePolicy");
    assertAllowedKeys(
        policy,
        Set.of("compensation", "mode"),
        "Workflow failurePolicy"
    );
    String mode = optionalText(
        policy.get("mode"),
        "FAIL_FAST"
    ).toUpperCase(Locale.ROOT);
    String compensation = optionalText(
        policy.get("compensation"),
        "NONE"
    ).toUpperCase(Locale.ROOT);
    if (!Set.of("FAIL_FAST", "CONTINUE").contains(mode)) {
      throw new IllegalArgumentException(
          "Workflow failurePolicy mode is invalid"
      );
    }
    if (!Set.of("NONE", "REVERSE_SUCCEEDED").contains(compensation)) {
      throw new IllegalArgumentException(
          "Workflow compensation policy is invalid"
      );
    }
    return new FailurePolicy(mode, compensation);
  }

  private void validateReferences(
      final Map<String, WorkflowNode> nodes,
      final List<WorkflowEdge> edges,
      final List<String> order,
      final Object output
  ) {
    Map<String, Integer> positions = new HashMap<>();
    for (int index = 0; index < order.size(); index++) {
      positions.put(order.get(index), index);
    }
    for (WorkflowNode node : nodes.values()) {
      validateReferenceTree(
          node.definition().get("input"),
          positions,
          positions.get(node.id()),
          "Workflow node " + node.id(),
          0
      );
      validateReferenceTree(
          node.definition().get("condition"),
          positions,
          positions.get(node.id()),
          "Workflow node " + node.id() + " condition",
          0
      );
      Object compensation = node.definition().get("compensation");
      if (compensation instanceof Map<?, ?> map) {
        validateReferenceTree(
            map.get("input"),
            positions,
            positions.get(node.id()) + 1,
            "Workflow node " + node.id() + " compensation",
            0
        );
      }
    }
    for (WorkflowEdge edge : edges) {
      validateReferenceTree(
          edge.condition(),
          positions,
          positions.get(edge.to()),
          "Workflow edge " + edge.from() + " -> " + edge.to(),
          0
      );
    }
    validateReferenceTree(
        output,
        positions,
        order.size(),
        "Workflow output",
        0
    );
  }

  private void validateReferenceTree(
      final Object value,
      final Map<String, Integer> positions,
      final int maximumPosition,
      final String label,
      final int depth
  ) {
    if (value == null) {
      return;
    }
    if (depth > 32) {
      throw new IllegalArgumentException(
          label + " exceeds maximum JSON depth"
      );
    }
    if (value instanceof Map<?, ?> map) {
      if (map.containsKey("$ref")) {
        if (map.size() != 1
            || !(map.get("$ref") instanceof String reference)) {
          throw new IllegalArgumentException(
              label + " $ref must be one string field"
          );
        }
        validateReference(
            reference,
            positions,
            maximumPosition,
            label
        );
        return;
      }
      map.values().forEach(nested -> validateReferenceTree(
          nested,
          positions,
          maximumPosition,
          label,
          depth + 1
      ));
    } else if (value instanceof List<?> list) {
      list.forEach(nested -> validateReferenceTree(
          nested,
          positions,
          maximumPosition,
          label,
          depth + 1
      ));
    }
  }

  private static void validateReference(
      final String reference,
      final Map<String, Integer> positions,
      final int maximumPosition,
      final String label
  ) {
    if (reference == null || reference.isBlank()
        || reference.length() > 512) {
      throw new IllegalArgumentException(label + " $ref is invalid");
    }
    String[] parts = reference.trim().split("\\.", -1);
    for (String part : parts) {
      if (!IDENTIFIER.matcher(part).matches()) {
        throw new IllegalArgumentException(
            label + " $ref contains an invalid path segment"
        );
      }
    }
    if ("input".equals(parts[0])) {
      return;
    }
    if (!"nodes".equals(parts[0]) || parts.length < 2
        || !positions.containsKey(parts[1])
        || positions.get(parts[1]) >= maximumPosition) {
      throw new IllegalArgumentException(
          label + " references unavailable node output: " + reference
      );
    }
    if (parts.length >= 3 && !"output".equals(parts[2])) {
      throw new IllegalArgumentException(
          label + " node reference must use nodes.<id>.output"
      );
    }
  }

  private void validateCondition(
      final Object value,
      final int depth,
      final String label
  ) {
    if (depth > 8 || !(value instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException(
          label + " must be a bounded object"
      );
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> condition = (Map<String, Object>) raw;
    Set<String> operators = new LinkedHashSet<>(condition.keySet());
    operators.retainAll(
        Set.of("all", "any", "equals", "isTrue", "not", "notEquals")
    );
    if (operators.size() != 1 || condition.size() != 1) {
      throw new IllegalArgumentException(
          label + " must contain exactly one supported operator"
      );
    }
    String operator = operators.iterator().next();
    Object operand = condition.get(operator);
    if (Set.of("all", "any").contains(operator)) {
      if (!(operand instanceof List<?> list)
          || list.isEmpty()
          || list.size() > 16) {
        throw new IllegalArgumentException(
            label + " boolean group is invalid"
        );
      }
      list.forEach(item -> validateCondition(item, depth + 1, label));
    } else if ("not".equals(operator)) {
      validateCondition(operand, depth + 1, label);
    } else if (operand == null) {
      throw new IllegalArgumentException(label + " operand is required");
    }
  }

  private void rejectExecutableFields(
      final Object value,
      final String path
  ) {
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        if (FORBIDDEN_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
          throw new IllegalArgumentException(
              path + " contains forbidden executable field: " + key
          );
        }
        rejectExecutableFields(entry.getValue(), path + "." + key);
      }
    } else if (value instanceof List<?> list) {
      for (int index = 0; index < list.size(); index++) {
        rejectExecutableFields(list.get(index), path + "[" + index + "]");
      }
    }
  }

  private static void assertAllowedKeys(
      final Map<String, Object> value,
      final Set<String> allowed,
      final String label
  ) {
    for (String key : value.keySet()) {
      if (!allowed.contains(key)) {
        throw new IllegalArgumentException(
            label + " contains unsupported field: " + key
        );
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> requiredMap(
      final Object value,
      final String label
  ) {
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> requiredMapList(
      final Object value,
      final String label
  ) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(label + " must be an array");
    }
    List<Map<String, Object>> result = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof Map<?, ?>)) {
        throw new IllegalArgumentException(
            label + " must contain objects"
        );
      }
      result.add((Map<String, Object>) item);
    }
    return result;
  }

  private static String requiredIdentifier(
      final Object value,
      final String label
  ) {
    String text = requiredText(value, label);
    if (!IDENTIFIER.matcher(text).matches()) {
      throw new IllegalArgumentException(label + " is invalid");
    }
    return text;
  }

  private static String requiredText(
      final Object value,
      final String label
  ) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
    return text.trim();
  }

  private static String optionalText(
      final Object value,
      final String fallback
  ) {
    return value instanceof String text && !text.isBlank()
        ? text.trim() : fallback;
  }

  private static int positiveInt(
      final Object value,
      final String label,
      final int maximum
  ) {
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException(label + " must be an integer");
    }
    int result = number.intValue();
    if (result <= 0 || result > maximum
        || number.doubleValue() != result) {
      throw new IllegalArgumentException(
          label + " must be between 1 and " + maximum
      );
    }
    return result;
  }

  /**
   * Immutable compiled execution graph.
   */
  public record CompiledWorkflow(
      List<WorkflowNode> nodes,
      List<WorkflowEdge> edges,
      List<String> topologicalOrder,
      List<DependencyReference> dependencies,
      WorkflowBudgets budgets,
      FailurePolicy failurePolicy,
      Object outputTemplate
  ) {
  }

  /**
   * One bounded declarative node.
   */
  public record WorkflowNode(
      String id,
      String type,
      Map<String, Object> definition
  ) {
  }

  /**
   * One directed acyclic dependency edge.
   */
  public record WorkflowEdge(
      String from,
      String to,
      Object condition
  ) {
  }

  /**
   * One exact Agent or Skill version reference.
   */
  public record DependencyReference(
      String nodeId,
      WorkflowDependencyType type,
      String resourceId,
      String resourceVersionId
  ) {
  }

  /**
   * Version-pinned execution budgets.
   */
  public record WorkflowBudgets(
      int maximumDurationSeconds,
      int maximumNodeExecutions,
      int maximumParallelism
  ) {
  }

  /**
   * Deterministic failure and compensation policy.
   */
  public record FailurePolicy(
      String mode,
      String compensation
  ) {
  }
}
