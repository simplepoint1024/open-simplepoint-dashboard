package org.simplepoint.plugin.ai.skill.service.designer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Edge;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Node;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.NodeMock;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.NodeType;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Port;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.PortDirection;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.PortKind;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Position;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestAssertion;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestCase;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Viewport;
import org.springframework.stereotype.Component;

/**
 * Converts immutable Skill Manifests to mutable designer documents and back.
 *
 * <p>The v1alpha1 adapter intentionally supports the structured workflow
 * grammar implemented by {@code SkillWorkflowPlanCompiler}. Designer edges are
 * normalized from the parent, branch, and order fields and are checked during
 * export so that visual and executable control flow cannot diverge.
 */
@Component
public class SkillDesignerManifestAdapter {

  private static final String API_VERSION = "simplepoint.io/v1alpha1";

  private static final String KIND = "Skill";

  private static final Set<String> STRUCTURAL_FIELDS = Set.of(
      "id",
      "type",
      "then",
      "else",
      "branches"
  );

  /** Converts a v1alpha1 Skill Manifest into a normalized designer document. */
  public SkillDesignerDocument fromManifest(
      final Map<String, Object> source
  ) {
    Map<String, Object> manifest = requireMap(source, "Skill Manifest");
    requireEquals(
        manifest.get("apiVersion"),
        API_VERSION,
        "Skill Manifest apiVersion"
    );
    requireEquals(manifest.get("kind"), KIND, "Skill Manifest kind");
    final Map<String, Object> metadata = requireMap(
        manifest.get("metadata"),
        "Skill Manifest metadata"
    );
    Map<String, Object> spec = requireMap(
        manifest.get("spec"),
        "Skill Manifest spec"
    );
    Map<String, Object> workflow = requireMap(
        spec.get("workflow"),
        "Skill Manifest workflow"
    );
    List<Map<String, Object>> steps = mapList(
        workflow.get("steps"),
        "Skill Manifest workflow steps",
        false
    );

    List<Node> nodes = new ArrayList<>();
    nodes.add(new Node(
        SkillDesignerDocument.INPUT_NODE_ID,
        NodeType.INPUT,
        null,
        null,
        0,
        Map.of(),
        new Position(0, 120)
    ));
    appendSteps(steps, null, null, 120, true, nodes);
    nodes.add(new Node(
        SkillDesignerDocument.OUTPUT_NODE_ID,
        NodeType.OUTPUT,
        null,
        null,
        steps.size() + 1,
        Map.of(),
        new Position((steps.size() + 1) * 260.0, 120)
    ));
    List<Node> validatedNodes = requireNodes(nodes);

    return new SkillDesignerDocument(
        SkillDesignerDocument.SCHEMA_VERSION,
        copyMap(metadata),
        requireMap(spec.get("inputSchema"), "Skill inputSchema"),
        requireMap(spec.get("outputSchema"), "Skill outputSchema"),
        mapList(spec.get("tools"), "Skill tools", false),
        mapList(spec.get("prompts"), "Skill prompts", true),
        mapList(spec.get("resources"), "Skill resources", true),
        validatedNodes,
        normalizedPorts(validatedNodes),
        normalizedEdges(validatedNodes),
        copyValue(workflow.get("output")),
        optionalMap(spec.get("budgets"), "Skill budgets"),
        optionalMap(spec.get("approvals"), "Skill approvals"),
        List.of(),
        new Viewport(0, 0, 1)
    );
  }

  /** Converts a normalized designer document into a v1alpha1 Skill Manifest. */
  public Map<String, Object> toManifest(
      final SkillDesignerDocument document
  ) {
    if (document == null) {
      throw new IllegalArgumentException("Skill Designer Document is required");
    }
    requireEquals(
        document.schemaVersion(),
        SkillDesignerDocument.SCHEMA_VERSION,
        "Skill Designer Document schemaVersion"
    );
    requireMap(document.metadata(), "Skill Designer metadata");
    requireMap(document.inputSchema(), "Skill Designer inputSchema");
    requireMap(document.outputSchema(), "Skill Designer outputSchema");
    mapList(document.tools(), "Skill Designer tools", false);
    mapList(document.prompts(), "Skill Designer prompts", true);
    mapList(document.resources(), "Skill Designer resources", true);

    List<Node> nodes = requireNodes(document.nodes());
    assertNormalizedPorts(nodes, document.ports());
    assertNormalizedEdges(nodes, document.edges());
    validateTests(nodes, document.tests());
    Set<String> consumed = new LinkedHashSet<>();
    List<Map<String, Object>> steps = encodeSequence(
        nodes,
        null,
        null,
        consumed
    );
    assertAllExecutableNodesConsumed(nodes, consumed);

    Map<String, Object> workflow = new LinkedHashMap<>();
    workflow.put("steps", steps);
    if (document.workflowOutput() != null) {
      workflow.put("output", copyValue(document.workflowOutput()));
    }

    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("inputSchema", copyMap(document.inputSchema()));
    spec.put("outputSchema", copyMap(document.outputSchema()));
    spec.put("workflow", workflow);
    spec.put("tools", copyMaps(document.tools()));
    if (document.prompts() != null) {
      spec.put("prompts", copyMaps(document.prompts()));
    }
    if (document.resources() != null) {
      spec.put("resources", copyMaps(document.resources()));
    }
    if (document.budgets() != null) {
      spec.put("budgets", copyMap(document.budgets()));
    }
    if (document.approvals() != null) {
      spec.put("approvals", copyMap(document.approvals()));
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("apiVersion", API_VERSION);
    result.put("kind", KIND);
    result.put("metadata", copyMap(document.metadata()));
    result.put("spec", spec);
    return result;
  }

  private void appendSteps(
      final List<Map<String, Object>> steps,
      final String parentNodeId,
      final String branchId,
      final double y,
      final boolean allowControlNodes,
      final List<Node> nodes
  ) {
    for (int index = 0; index < steps.size(); index++) {
      Map<String, Object> step = steps.get(index);
      String id = requiredText(step.get("id"), "Workflow step ID");
      NodeType type = nodeType(step.get("type"));
      if (!allowControlNodes
          && (type == NodeType.CONDITION || type == NodeType.PARALLEL)) {
        throw new IllegalArgumentException(
            "Nested workflow control node is not supported in v1alpha1: " + id
        );
      }
      Position position = new Position((index + 1) * 260.0, y);
      Map<String, Object> configuration = configuration(step, type);
      nodes.add(new Node(
          id,
          type,
          parentNodeId,
          branchId,
          index,
          configuration,
          position
      ));
      if (type == NodeType.CONDITION) {
        appendSteps(
            emptyIfNull(mapList(
                step.get("then"),
                "Condition then steps",
                true
            )),
            id,
            "then",
            y + 220,
            false,
            nodes
        );
        appendSteps(
            emptyIfNull(mapList(
                step.get("else"),
                "Condition else steps",
                true
            )),
            id,
            "else",
            y + 440,
            false,
            nodes
        );
      } else if (type == NodeType.PARALLEL) {
        List<Map<String, Object>> branches = mapList(
            step.get("branches"),
            "Parallel branches",
            false
        );
        for (int branchIndex = 0;
             branchIndex < branches.size();
             branchIndex++) {
          Map<String, Object> branch = branches.get(branchIndex);
          String nestedBranchId = requiredText(
              branch.get("id"),
              "Parallel branch ID"
          );
          appendSteps(
              mapList(
                  branch.get("steps"),
                  "Parallel branch steps",
                  false
              ),
              id,
              nestedBranchId,
              y + 220 + branchIndex * 180.0,
              false,
              nodes
          );
        }
      }
    }
  }

  private Map<String, Object> configuration(
      final Map<String, Object> step,
      final NodeType type
  ) {
    Map<String, Object> result = new LinkedHashMap<>();
    step.forEach((key, value) -> {
      if (!STRUCTURAL_FIELDS.contains(key)) {
        result.put(key, copyValue(value));
      }
    });
    if (type == NodeType.CONDITION) {
      result.put("condition", copyValue(step.get("condition")));
    } else if (type == NodeType.PARALLEL) {
      List<Map<String, Object>> branches = mapList(
          step.get("branches"),
          "Parallel branches",
          false
      );
      List<Map<String, Object>> descriptors = new ArrayList<>();
      for (Map<String, Object> branch : branches) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put(
            "id",
            requiredText(branch.get("id"), "Parallel branch ID")
        );
        descriptors.add(descriptor);
      }
      result.put("branches", descriptors);
    }
    return result;
  }

  private List<Map<String, Object>> encodeSequence(
      final List<Node> nodes,
      final String parentNodeId,
      final String branchId,
      final Set<String> consumed
  ) {
    List<Node> sequence = sequence(nodes, parentNodeId, branchId);
    List<Map<String, Object>> result = new ArrayList<>(sequence.size());
    for (Node node : sequence) {
      consumed.add(node.id());
      result.add(encodeNode(node, nodes, consumed));
    }
    return result;
  }

  private Map<String, Object> encodeNode(
      final Node node,
      final List<Node> nodes,
      final Set<String> consumed
  ) {
    Map<String, Object> configuration = requireMap(
        node.configuration(),
        "Designer node configuration"
    );
    for (String key : STRUCTURAL_FIELDS) {
      if (configuration.containsKey(key)
          && !(node.type() == NodeType.CONDITION
              && "condition".equals(key))
          && !(node.type() == NodeType.PARALLEL
              && "branches".equals(key))) {
        throw new IllegalArgumentException(
            "Designer node configuration contains structural field: " + key
        );
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", node.id());
    result.put("type", node.type().name().toLowerCase(Locale.ROOT));
    if (node.type() == NodeType.CONDITION) {
      if (!configuration.containsKey("condition")) {
        throw new IllegalArgumentException(
            "Condition node must define condition: " + node.id()
        );
      }
      result.put("condition", copyValue(configuration.get("condition")));
      List<Map<String, Object>> whenTrue = encodeSequence(
          nodes,
          node.id(),
          "then",
          consumed
      );
      List<Map<String, Object>> whenFalse = encodeSequence(
          nodes,
          node.id(),
          "else",
          consumed
      );
      if (!whenTrue.isEmpty()) {
        result.put("then", whenTrue);
      }
      if (!whenFalse.isEmpty()) {
        result.put("else", whenFalse);
      }
      return result;
    }
    if (node.type() == NodeType.PARALLEL) {
      List<Map<String, Object>> descriptors = mapList(
          configuration.get("branches"),
          "Parallel node branches",
          false
      );
      List<Map<String, Object>> branches = new ArrayList<>();
      Set<String> branchIds = new HashSet<>();
      for (Map<String, Object> descriptor : descriptors) {
        String nestedBranchId = requiredText(
            descriptor.get("id"),
            "Parallel branch ID"
        );
        if (!branchIds.add(nestedBranchId)) {
          throw new IllegalArgumentException(
              "Duplicate parallel branch ID: " + nestedBranchId
          );
        }
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("id", nestedBranchId);
        branch.put(
            "steps",
            encodeSequence(nodes, node.id(), nestedBranchId, consumed)
        );
        branches.add(branch);
      }
      result.put("branches", branches);
      return result;
    }
    configuration.forEach((key, value) ->
        result.put(key, copyValue(value)));
    return result;
  }

  private List<Node> requireNodes(final List<Node> source) {
    if (source == null) {
      throw new IllegalArgumentException("Skill Designer nodes are required");
    }
    List<Node> nodes = new ArrayList<>(source);
    Set<String> ids = new HashSet<>();
    for (Node node : nodes) {
      if (node == null || node.type() == null || node.position() == null) {
        throw new IllegalArgumentException("Skill Designer node is incomplete");
      }
      requiredText(node.id(), "Skill Designer node ID");
      if (!ids.add(node.id())) {
        throw new IllegalArgumentException(
            "Duplicate Skill Designer node ID: " + node.id()
        );
      }
      if (node.order() < 0) {
        throw new IllegalArgumentException(
            "Skill Designer node order must not be negative: " + node.id()
        );
      }
      boolean nested = node.parentNodeId() != null;
      if (nested != (node.branchId() != null)) {
        throw new IllegalArgumentException(
            "Nested Skill Designer node requires parent and branch: "
                + node.id()
        );
      }
    }
    assertVirtualNode(nodes, SkillDesignerDocument.INPUT_NODE_ID, NodeType.INPUT);
    assertVirtualNode(
        nodes,
        SkillDesignerDocument.OUTPUT_NODE_ID,
        NodeType.OUTPUT
    );
    return nodes;
  }

  private void assertVirtualNode(
      final List<Node> nodes,
      final String id,
      final NodeType type
  ) {
    List<Node> matches = nodes.stream()
        .filter(node -> id.equals(node.id()))
        .toList();
    long typeCount = nodes.stream()
        .filter(node -> node.type() == type)
        .count();
    if (matches.size() != 1 || typeCount != 1
        || matches.getFirst().type() != type
        || matches.getFirst().parentNodeId() != null) {
      throw new IllegalArgumentException(
          "Skill Designer requires one top-level " + type + " node"
      );
    }
  }

  private List<Node> sequence(
      final List<Node> nodes,
      final String parentNodeId,
      final String branchId
  ) {
    List<Node> result = nodes.stream()
        .filter(node -> node.type() != NodeType.INPUT
            && node.type() != NodeType.OUTPUT)
        .filter(node -> Objects.equals(parentNodeId, node.parentNodeId()))
        .filter(node -> Objects.equals(branchId, node.branchId()))
        .sorted(Comparator.comparingInt(Node::order).thenComparing(Node::id))
        .toList();
    for (int index = 0; index < result.size(); index++) {
      if (result.get(index).order() != index) {
        throw new IllegalArgumentException(
            "Skill Designer sequence order must be contiguous at "
                + result.get(index).id()
        );
      }
    }
    return result;
  }

  private void assertAllExecutableNodesConsumed(
      final List<Node> nodes,
      final Set<String> consumed
  ) {
    for (Node node : nodes) {
      if (node.type() != NodeType.INPUT
          && node.type() != NodeType.OUTPUT
          && !consumed.contains(node.id())) {
        throw new IllegalArgumentException(
            "Skill Designer node is outside a supported branch: " + node.id()
        );
      }
    }
  }

  private void assertNormalizedEdges(
      final List<Node> nodes,
      final List<Edge> actual
  ) {
    if (actual == null) {
      throw new IllegalArgumentException("Skill Designer edges are required");
    }
    Map<EdgeSignature, Integer> expectedCounts = edgeCounts(
        normalizedEdges(nodes)
    );
    Map<EdgeSignature, Integer> actualCounts = edgeCounts(actual);
    if (!expectedCounts.equals(actualCounts)) {
      throw new IllegalArgumentException(
          "Skill Designer edges do not match structured workflow order"
      );
    }
  }

  private void assertNormalizedPorts(
      final List<Node> nodes,
      final List<Port> actual
  ) {
    if (actual == null) {
      throw new IllegalArgumentException("Skill Designer ports are required");
    }
    Set<PortSignature> expected = portSignatures(normalizedPorts(nodes));
    Set<PortSignature> provided = portSignatures(actual);
    if (!expected.equals(provided)) {
      throw new IllegalArgumentException(
          "Skill Designer ports do not match node semantics"
      );
    }
  }

  private List<Port> normalizedPorts(final List<Node> nodes) {
    List<Port> result = new ArrayList<>();
    for (Node node : nodes) {
      if (node.type() != NodeType.INPUT) {
        result.add(port(node.id(), "in", PortDirection.INPUT, null));
      }
      if (node.type() == NodeType.OUTPUT) {
        continue;
      }
      if (node.type() == NodeType.CONDITION) {
        result.add(port(
            node.id(),
            "then",
            PortDirection.OUTPUT,
            "True"
        ));
        result.add(port(
            node.id(),
            "else",
            PortDirection.OUTPUT,
            "False"
        ));
      } else if (node.type() == NodeType.PARALLEL) {
        List<Map<String, Object>> branches = mapList(
            node.configuration().get("branches"),
            "Parallel node branches",
            false
        );
        for (Map<String, Object> branch : branches) {
          String branchId = requiredText(
              branch.get("id"),
              "Parallel branch ID"
          );
          result.add(port(
              node.id(),
              branchId,
              PortDirection.OUTPUT,
              branchId
          ));
        }
      } else {
        result.add(port(node.id(), "out", PortDirection.OUTPUT, null));
      }
    }
    return result;
  }

  private Port port(
      final String nodeId,
      final String id,
      final PortDirection direction,
      final String label
  ) {
    return new Port(
        nodeId,
        id,
        direction,
        PortKind.CONTROL,
        label,
        true,
        false
    );
  }

  private Set<PortSignature> portSignatures(final List<Port> ports) {
    Set<PortSignature> result = new LinkedHashSet<>();
    for (Port port : ports) {
      if (port == null || port.direction() == null || port.kind() == null) {
        throw new IllegalArgumentException("Skill Designer port is incomplete");
      }
      PortSignature signature = new PortSignature(
          requiredText(port.nodeId(), "Port node ID"),
          requiredText(port.id(), "Port ID"),
          port.direction(),
          port.kind(),
          port.required(),
          port.multiple()
      );
      if (!result.add(signature)) {
        throw new IllegalArgumentException(
            "Duplicate Skill Designer port: "
                + port.nodeId() + "/" + port.id()
        );
      }
    }
    return result;
  }

  private void validateTests(
      final List<Node> nodes,
      final List<TestCase> tests
  ) {
    if (tests == null) {
      throw new IllegalArgumentException("Skill Designer tests are required");
    }
    Map<String, NodeType> nodeTypes = new HashMap<>();
    nodes.forEach(node -> nodeTypes.put(node.id(), node.type()));
    Set<String> testIds = new HashSet<>();
    for (TestCase test : tests) {
      if (test == null || test.input() == null
          || test.mocks() == null || test.assertions() == null) {
        throw new IllegalArgumentException(
            "Skill Designer test case is incomplete"
        );
      }
      String testId = requiredText(test.id(), "Test case ID");
      requiredText(test.name(), "Test case name");
      if (!testIds.add(testId)) {
        throw new IllegalArgumentException("Duplicate test case ID: " + testId);
      }
      validateMocks(testId, test.mocks(), nodeTypes);
      validateAssertions(testId, test.assertions(), nodeTypes.keySet());
    }
  }

  private void validateMocks(
      final String testId,
      final List<NodeMock> mocks,
      final Map<String, NodeType> nodeTypes
  ) {
    Set<String> nodeIds = new HashSet<>();
    for (NodeMock mock : mocks) {
      if (mock == null || mock.mode() == null) {
        throw new IllegalArgumentException(
            "Test case mock is incomplete: " + testId
        );
      }
      String nodeId = requiredText(mock.nodeId(), "Mock node ID");
      NodeType type = nodeTypes.get(nodeId);
      if (type != NodeType.TOOL
          && type != NodeType.PROMPT
          && type != NodeType.RESOURCE) {
        throw new IllegalArgumentException(
            "Test case mock must target an MCP node: " + nodeId
        );
      }
      if (!nodeIds.add(nodeId)) {
        throw new IllegalArgumentException(
            "Duplicate test case mock node: " + nodeId
        );
      }
      if (mock.mode() == SkillDesignerDocument.MockMode.ERROR
          && (mock.errorCode() == null || mock.errorCode().isBlank())) {
        throw new IllegalArgumentException(
            "Error mock must define errorCode: " + nodeId
        );
      }
    }
  }

  private void validateAssertions(
      final String testId,
      final List<TestAssertion> assertions,
      final Set<String> nodeIds
  ) {
    Set<String> assertionIds = new HashSet<>();
    for (TestAssertion assertion : assertions) {
      if (assertion == null || assertion.operator() == null) {
        throw new IllegalArgumentException(
            "Test assertion is incomplete: " + testId
        );
      }
      String assertionId = requiredText(assertion.id(), "Assertion ID");
      String sourceNodeId = requiredText(
          assertion.sourceNodeId(),
          "Assertion source node ID"
      );
      if (!nodeIds.contains(sourceNodeId)) {
        throw new IllegalArgumentException(
            "Assertion references unknown node: " + sourceNodeId
        );
      }
      if (!assertionIds.add(assertionId)) {
        throw new IllegalArgumentException(
            "Duplicate assertion ID: " + assertionId
        );
      }
    }
  }

  private List<Edge> normalizedEdges(final List<Node> nodes) {
    List<Edge> result = new ArrayList<>();
    List<Node> topLevel = sequence(nodes, null, null);
    if (topLevel.isEmpty()) {
      addEdge(
          result,
          SkillDesignerDocument.INPUT_NODE_ID,
          null,
          SkillDesignerDocument.OUTPUT_NODE_ID
      );
      return result;
    }
    addEdge(
        result,
        SkillDesignerDocument.INPUT_NODE_ID,
        null,
        topLevel.getFirst().id()
    );
    for (int index = 0; index < topLevel.size(); index++) {
      Node node = topLevel.get(index);
      String successor = index + 1 < topLevel.size()
          ? topLevel.get(index + 1).id()
          : SkillDesignerDocument.OUTPUT_NODE_ID;
      addNodeEdges(node, successor, nodes, result);
    }
    return result;
  }

  private void addNodeEdges(
      final Node node,
      final String successor,
      final List<Node> nodes,
      final List<Edge> result
  ) {
    if (node.type() == NodeType.CONDITION) {
      addBranchEdges(node.id(), "then", successor, nodes, result);
      addBranchEdges(node.id(), "else", successor, nodes, result);
      return;
    }
    if (node.type() == NodeType.PARALLEL) {
      List<Map<String, Object>> branches = mapList(
          node.configuration().get("branches"),
          "Parallel node branches",
          false
      );
      for (Map<String, Object> branch : branches) {
        addBranchEdges(
            node.id(),
            requiredText(branch.get("id"), "Parallel branch ID"),
            successor,
            nodes,
            result
        );
      }
      return;
    }
    addEdge(result, node.id(), null, successor);
  }

  private void addBranchEdges(
      final String parentNodeId,
      final String branchId,
      final String successor,
      final List<Node> nodes,
      final List<Edge> result
  ) {
    List<Node> branch = sequence(nodes, parentNodeId, branchId);
    if (branch.isEmpty()) {
      addEdge(result, parentNodeId, branchId, successor);
      return;
    }
    addEdge(result, parentNodeId, branchId, branch.getFirst().id());
    for (int index = 0; index < branch.size(); index++) {
      String target = index + 1 < branch.size()
          ? branch.get(index + 1).id() : successor;
      addEdge(result, branch.get(index).id(), null, target);
    }
  }

  private void addEdge(
      final List<Edge> target,
      final String source,
      final String sourceHandle,
      final String destination
  ) {
    String id = "edge-" + target.size();
    String effectiveSourceHandle = sourceHandle == null ? "out" : sourceHandle;
    target.add(new Edge(
        id,
        source,
        effectiveSourceHandle,
        destination,
        "in"
    ));
  }

  private Map<EdgeSignature, Integer> edgeCounts(final List<Edge> edges) {
    Map<EdgeSignature, Integer> result = new HashMap<>();
    for (Edge edge : edges) {
      if (edge == null) {
        throw new IllegalArgumentException("Skill Designer edge is required");
      }
      EdgeSignature signature = new EdgeSignature(
          requiredText(edge.sourceNodeId(), "Edge source node ID"),
          edge.sourceHandle(),
          requiredText(edge.targetNodeId(), "Edge target node ID"),
          edge.targetHandle()
      );
      result.merge(signature, 1, Integer::sum);
    }
    return result;
  }

  private NodeType nodeType(final Object value) {
    String type = requiredText(value, "Workflow step type")
        .toUpperCase(Locale.ROOT);
    try {
      NodeType result = NodeType.valueOf(type);
      if (result == NodeType.INPUT || result == NodeType.OUTPUT) {
        throw new IllegalArgumentException(
            "Workflow step type is reserved: " + value
        );
      }
      return result;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Unsupported workflow step type: " + value,
          exception
      );
    }
  }

  private static Map<String, Object> optionalMap(
      final Object value,
      final String label
  ) {
    return value == null ? null : requireMap(value, label);
  }

  private static Map<String, Object> requireMap(
      final Object value,
      final String label
  ) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, nested) -> {
      if (!(key instanceof String text)) {
        throw new IllegalArgumentException(label + " keys must be strings");
      }
      result.put(text, copyValue(nested));
    });
    return result;
  }

  private static List<Map<String, Object>> mapList(
      final Object value,
      final String label,
      final boolean optional
  ) {
    if (value == null && optional) {
      return null;
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(label + " must be an array");
    }
    List<Map<String, Object>> result = new ArrayList<>(list.size());
    for (Object item : list) {
      result.add(requireMap(item, label + " item"));
    }
    return result;
  }

  private static List<Map<String, Object>> mapList(
      final List<Map<String, Object>> value,
      final String label,
      final boolean optional
  ) {
    return mapList((Object) value, label, optional);
  }

  private static List<Map<String, Object>> emptyIfNull(
      final List<Map<String, Object>> value
  ) {
    return value == null ? List.of() : value;
  }

  private static String requiredText(
      final Object value,
      final String label
  ) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
    return text;
  }

  private static void requireEquals(
      final Object actual,
      final Object expected,
      final String label
  ) {
    if (!Objects.equals(actual, expected)) {
      throw new IllegalArgumentException(label + " must equal " + expected);
    }
  }

  private static List<Map<String, Object>> copyMaps(
      final List<Map<String, Object>> source
  ) {
    List<Map<String, Object>> result = new ArrayList<>(source.size());
    source.forEach(value -> result.add(copyMap(value)));
    return result;
  }

  private static Map<String, Object> copyMap(
      final Map<String, Object> source
  ) {
    return requireMap(source, "JSON object");
  }

  private static Object copyValue(final Object source) {
    if (source instanceof Map<?, ?> map) {
      return requireMap(map, "JSON object");
    }
    if (source instanceof List<?> list) {
      List<Object> result = new ArrayList<>(list.size());
      list.forEach(value -> result.add(copyValue(value)));
      return result;
    }
    return source;
  }

  private record EdgeSignature(
      String sourceNodeId,
      String sourceHandle,
      String targetNodeId,
      String targetHandle
  ) {
  }

  private record PortSignature(
      String nodeId,
      String id,
      PortDirection direction,
      PortKind kind,
      boolean required,
      boolean multiple
  ) {
  }
}
