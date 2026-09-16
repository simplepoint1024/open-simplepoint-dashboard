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
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDiagnostic;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDiagnostic.Severity;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Edge;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.MockMode;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Node;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.NodeMock;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.NodeType;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Port;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.PortDirection;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.PortKind;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestAssertion;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestCase;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.springframework.stereotype.Component;

/** Aggregates locatable design-time diagnostics before Manifest compilation. */
@Component
public class SkillDesignerValidator {

  private static final int MAXIMUM_DIAGNOSTICS = 256;

  private static final int MAXIMUM_EXECUTABLE_NODES = 128;

  private static final int MAXIMUM_PARALLEL_BRANCHES = 16;

  private static final Pattern NODE_ID =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private static final Pattern ALIAS =
      Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,63}$");

  private static final Pattern REFERENCE_SEGMENT =
      Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

  private static final Map<NodeType, Set<String>> CONFIGURATION_FIELDS = Map.of(
      NodeType.INPUT, Set.of(),
      NodeType.OUTPUT, Set.of(),
      NodeType.TOOL, Set.of("arguments", "tool"),
      NodeType.PROMPT, Set.of("arguments", "prompt"),
      NodeType.RESOURCE, Set.of("resource", "uri"),
      NodeType.CONDITION, Set.of("condition"),
      NodeType.PARALLEL, Set.of("branches")
  );

  private final SkillJsonSchemaValidator schemaValidator;

  /** Creates the aggregate validator. */
  public SkillDesignerValidator(
      final SkillJsonSchemaValidator schemaValidator
  ) {
    this.schemaValidator = schemaValidator;
  }

  /** Returns all deterministic diagnostics in document order. */
  public List<SkillDesignerDiagnostic> validate(
      final SkillDesignerDocument document
  ) {
    DiagnosticCollector diagnostics = new DiagnosticCollector();
    if (document == null) {
      diagnostics.error(
          "SKILL_DESIGNER_DOCUMENT_REQUIRED",
          "Skill Designer Document is required",
          null,
          null,
          ""
      );
      return diagnostics.result();
    }
    validateDocumentFields(document, diagnostics);
    ValidationContext context = validateNodes(document.nodes(), diagnostics);
    validatePorts(document.ports(), context, diagnostics);
    validateEdges(document.edges(), context, diagnostics);
    BindingAliases aliases = validateBindings(document, diagnostics);
    validateNodeConfigurations(context, aliases, diagnostics);
    validateReferences(document, context, diagnostics);
    validateTests(document.tests(), context, diagnostics);
    validateSchemas(document, diagnostics);
    return diagnostics.result();
  }

  private void validateDocumentFields(
      final SkillDesignerDocument document,
      final DiagnosticCollector diagnostics
  ) {
    if (!SkillDesignerDocument.SCHEMA_VERSION.equals(
        document.schemaVersion())) {
      diagnostics.error(
          "SKILL_DESIGNER_VERSION_UNSUPPORTED",
          "Unsupported Skill Designer Document schemaVersion",
          null,
          "schemaVersion",
          "/schemaVersion"
      );
    }
    requireMap(document.metadata(), "metadata", "/metadata", diagnostics);
    if (document.metadata() != null) {
      String name = text(document.metadata().get("name"));
      if (name == null || !ALIAS.matcher(name).matches()) {
        diagnostics.error(
            "SKILL_DESIGNER_METADATA_NAME_INVALID",
            "Skill metadata name is missing or invalid",
            null,
            "metadata.name",
            "/metadata/name"
        );
      }
      if (text(document.metadata().get("version")) == null) {
        diagnostics.error(
            "SKILL_DESIGNER_METADATA_VERSION_REQUIRED",
            "Skill metadata version is required",
            null,
            "metadata.version",
            "/metadata/version"
        );
      }
      Set<String> allowed = Set.of(
          "description",
          "labels",
          "name",
          "title",
          "version"
      );
      document.metadata().keySet().stream()
          .filter(key -> !allowed.contains(key))
          .forEach(key -> diagnostics.error(
              "SKILL_DESIGNER_METADATA_FIELD_UNSUPPORTED",
              "Skill metadata contains an unsupported field: " + key,
              null,
              "metadata." + key,
              "/metadata/" + escape(key)
          ));
    }
    requireMap(
        document.inputSchema(),
        "inputSchema",
        "/inputSchema",
        diagnostics
    );
    requireMap(
        document.outputSchema(),
        "outputSchema",
        "/outputSchema",
        diagnostics
    );
    if (document.tools() == null) {
      diagnostics.error(
          "SKILL_DESIGNER_FIELD_REQUIRED",
          "Skill Designer tools are required",
          null,
          "tools",
          "/tools"
      );
    }
    if (document.nodes() == null) {
      diagnostics.error(
          "SKILL_DESIGNER_FIELD_REQUIRED",
          "Skill Designer nodes are required",
          null,
          "nodes",
          "/nodes"
      );
    }
    if (document.ports() == null) {
      diagnostics.error(
          "SKILL_DESIGNER_FIELD_REQUIRED",
          "Skill Designer ports are required",
          null,
          "ports",
          "/ports"
      );
    }
    if (document.edges() == null) {
      diagnostics.error(
          "SKILL_DESIGNER_FIELD_REQUIRED",
          "Skill Designer edges are required",
          null,
          "edges",
          "/edges"
      );
    }
    if (document.tests() == null) {
      diagnostics.error(
          "SKILL_DESIGNER_FIELD_REQUIRED",
          "Skill Designer tests are required",
          null,
          "tests",
          "/tests"
      );
    }
    if (document.viewport() != null && document.viewport().zoom() <= 0) {
      diagnostics.error(
          "SKILL_DESIGNER_VIEWPORT_INVALID",
          "Skill Designer viewport zoom must be greater than zero",
          null,
          "viewport.zoom",
          "/viewport/zoom"
      );
    }
  }

  private ValidationContext validateNodes(
      final List<Node> source,
      final DiagnosticCollector diagnostics
  ) {
    List<Node> nodes = source == null ? List.of() : source;
    if (nodes.size() < 2 || nodes.size() > 130) {
      diagnostics.error(
          "SKILL_DESIGNER_NODE_COUNT_INVALID",
          "Skill Designer must contain between 2 and 130 nodes",
          null,
          "nodes",
          "/nodes"
      );
    }
    Map<String, NodeRef> byId = new LinkedHashMap<>();
    Map<SequenceKey, List<NodeRef>> sequences = new LinkedHashMap<>();
    int executableCount = 0;
    for (int index = 0; index < nodes.size(); index++) {
      Node node = nodes.get(index);
      String pointer = "/nodes/" + index;
      if (node == null) {
        diagnostics.error(
            "SKILL_DESIGNER_NODE_INVALID",
            "Skill Designer node must be an object",
            null,
            null,
            pointer
        );
        continue;
      }
      String id = trim(node.id());
      if (id == null) {
        diagnostics.error(
            "SKILL_DESIGNER_NODE_ID_REQUIRED",
            "Skill Designer node ID is required",
            null,
            "id",
            pointer + "/id"
        );
      } else if (!isVirtualId(id) && !NODE_ID.matcher(id).matches()) {
        diagnostics.error(
            "SKILL_DESIGNER_NODE_ID_INVALID",
            "Skill Designer node ID has an invalid format",
            id,
            "id",
            pointer + "/id"
        );
      }
      if (node.type() == null) {
        diagnostics.error(
            "SKILL_DESIGNER_NODE_TYPE_REQUIRED",
            "Skill Designer node type is required",
            id,
            "type",
            pointer + "/type"
        );
      }
      if (node.position() == null) {
        diagnostics.error(
            "SKILL_DESIGNER_NODE_POSITION_REQUIRED",
            "Skill Designer node position is required",
            id,
            "position",
            pointer + "/position"
        );
      }
      if (node.configuration() == null) {
        diagnostics.error(
            "SKILL_DESIGNER_NODE_CONFIGURATION_REQUIRED",
            "Skill Designer node configuration is required",
            id,
            "configuration",
            pointer + "/configuration"
        );
      }
      if (node.order() < 0) {
        diagnostics.error(
            "SKILL_DESIGNER_NODE_ORDER_INVALID",
            "Skill Designer node order must not be negative",
            id,
            "order",
            pointer + "/order"
        );
      }
      NodeRef ref = new NodeRef(node, index);
      if (id != null && byId.putIfAbsent(id, ref) != null) {
        diagnostics.error(
            "SKILL_DESIGNER_NODE_DUPLICATE",
            "Duplicate Skill Designer node ID: " + id,
            id,
            "id",
            pointer + "/id"
        );
      }
      if (node.type() != NodeType.INPUT && node.type() != NodeType.OUTPUT) {
        executableCount++;
        sequences.computeIfAbsent(
            new SequenceKey(node.parentNodeId(), node.branchId()),
            ignored -> new ArrayList<>()
        ).add(ref);
      }
      boolean hasParent = trim(node.parentNodeId()) != null;
      boolean hasBranch = trim(node.branchId()) != null;
      if (hasParent != hasBranch) {
        diagnostics.error(
            "SKILL_DESIGNER_NODE_PARENT_INVALID",
            "Nested node requires both parentNodeId and branchId",
            id,
            "parentNodeId",
            pointer + "/parentNodeId"
        );
      }
    }
    if (executableCount > MAXIMUM_EXECUTABLE_NODES) {
      diagnostics.error(
          "SKILL_DESIGNER_NODE_LIMIT_EXCEEDED",
          "Skill Designer workflow exceeds 128 executable nodes",
          null,
          "nodes",
          "/nodes"
      );
    }
    validateVirtualNode(
        SkillDesignerDocument.INPUT_NODE_ID,
        NodeType.INPUT,
        byId,
        diagnostics
    );
    validateVirtualNode(
        SkillDesignerDocument.OUTPUT_NODE_ID,
        NodeType.OUTPUT,
        byId,
        diagnostics
    );
    ValidationContext context = new ValidationContext(byId, sequences);
    validateParentBranches(context, diagnostics);
    validateSequenceOrders(context, diagnostics);
    long mcpNodes = nodes.stream()
        .filter(Objects::nonNull)
        .map(Node::type)
        .filter(type -> type == NodeType.TOOL
            || type == NodeType.PROMPT
            || type == NodeType.RESOURCE)
        .count();
    if (mcpNodes == 0) {
      diagnostics.error(
          "SKILL_DESIGNER_WORKFLOW_EMPTY",
          "Skill Designer workflow must contain an executable MCP node",
          null,
          "nodes",
          "/nodes"
      );
    }
    return context;
  }

  private void validateVirtualNode(
      final String id,
      final NodeType type,
      final Map<String, NodeRef> byId,
      final DiagnosticCollector diagnostics
  ) {
    NodeRef ref = byId.get(id);
    if (ref == null || ref.node().type() != type
        || trim(ref.node().parentNodeId()) != null) {
      diagnostics.error(
          "SKILL_DESIGNER_VIRTUAL_NODE_INVALID",
          "Skill Designer requires one top-level " + type + " node",
          id,
          null,
          "/nodes"
      );
    }
  }

  private void validateParentBranches(
      final ValidationContext context,
      final DiagnosticCollector diagnostics
  ) {
    for (NodeRef ref : context.byId().values()) {
      Node node = ref.node();
      String parentId = trim(node.parentNodeId());
      if (parentId == null) {
        continue;
      }
      NodeRef parent = context.byId().get(parentId);
      String pointer = "/nodes/" + ref.index();
      if (parent == null || parent.node().type() != NodeType.CONDITION
          && parent.node().type() != NodeType.PARALLEL) {
        diagnostics.error(
            "SKILL_DESIGNER_NODE_PARENT_INVALID",
            "Nested node parent must be a Condition or Parallel node",
            node.id(),
            "parentNodeId",
            pointer + "/parentNodeId"
        );
        continue;
      }
      if (node.type() == NodeType.CONDITION
          || node.type() == NodeType.PARALLEL) {
        diagnostics.error(
            "SKILL_DESIGNER_CONTROL_NESTING_UNSUPPORTED",
            "Nested workflow control node is not supported in v1alpha1",
            node.id(),
            "parentNodeId",
            pointer + "/parentNodeId"
        );
      }
      Set<String> branches = branchIds(parent.node());
      if (!branches.contains(node.branchId())) {
        diagnostics.error(
            "SKILL_DESIGNER_BRANCH_INVALID",
            "Node references an unknown parent branch: " + node.branchId(),
            node.id(),
            "branchId",
            pointer + "/branchId"
        );
      }
    }
  }

  private void validateSequenceOrders(
      final ValidationContext context,
      final DiagnosticCollector diagnostics
  ) {
    for (List<NodeRef> sequence : context.sequences().values()) {
      List<NodeRef> ordered = ordered(sequence);
      for (int index = 0; index < ordered.size(); index++) {
        NodeRef ref = ordered.get(index);
        if (ref.node().order() != index) {
          diagnostics.error(
              "SKILL_DESIGNER_NODE_ORDER_INVALID",
              "Sequence order must be contiguous from zero",
              ref.node().id(),
              "order",
              "/nodes/" + ref.index() + "/order"
          );
        }
      }
    }
  }

  private void validatePorts(
      final List<Port> source,
      final ValidationContext context,
      final DiagnosticCollector diagnostics
  ) {
    List<Port> ports = source == null ? List.of() : source;
    if (ports.size() > 1024) {
      diagnostics.error(
          "SKILL_DESIGNER_PORT_LIMIT_EXCEEDED",
          "Skill Designer exceeds 1024 ports",
          null,
          "ports",
          "/ports"
      );
    }
    Map<PortKey, PortRef> actual = new LinkedHashMap<>();
    for (int index = 0; index < ports.size(); index++) {
      Port port = ports.get(index);
      String pointer = "/ports/" + index;
      if (port == null || trim(port.nodeId()) == null
          || trim(port.id()) == null || port.direction() == null
          || port.kind() == null) {
        diagnostics.error(
            "SKILL_DESIGNER_PORT_INVALID",
            "Skill Designer port is incomplete",
            port == null ? null : port.nodeId(),
            null,
            pointer
        );
        continue;
      }
      if (!context.byId().containsKey(port.nodeId())) {
        diagnostics.error(
            "SKILL_DESIGNER_PORT_NODE_UNKNOWN",
            "Port references an unknown node: " + port.nodeId(),
            port.nodeId(),
            null,
            pointer + "/nodeId"
        );
      }
      PortKey key = new PortKey(port.nodeId(), port.id());
      if (actual.putIfAbsent(key, new PortRef(port, index)) != null) {
        diagnostics.error(
            "SKILL_DESIGNER_PORT_DUPLICATE",
            "Duplicate Skill Designer port: "
                + port.nodeId() + "/" + port.id(),
            port.nodeId(),
            null,
            pointer
        );
      }
    }
    Map<PortKey, Port> expected = expectedPorts(context);
    for (Map.Entry<PortKey, Port> entry : expected.entrySet()) {
      PortRef provided = actual.get(entry.getKey());
      if (provided == null) {
        diagnostics.error(
            "SKILL_DESIGNER_PORT_MISSING",
            "Required node port is missing: " + entry.getKey().id(),
            entry.getKey().nodeId(),
            null,
            "/ports"
        );
      } else if (!samePortSemantics(provided.port(), entry.getValue())) {
        diagnostics.error(
            "SKILL_DESIGNER_PORT_SEMANTICS_INVALID",
            "Port direction or semantics do not match its node type",
            entry.getKey().nodeId(),
            null,
            "/ports/" + provided.index()
        );
      }
    }
    for (Map.Entry<PortKey, PortRef> entry : actual.entrySet()) {
      if (!expected.containsKey(entry.getKey())) {
        diagnostics.error(
            "SKILL_DESIGNER_PORT_UNEXPECTED",
            "Node exposes an unsupported port: " + entry.getKey().id(),
            entry.getKey().nodeId(),
            null,
            "/ports/" + entry.getValue().index()
        );
      }
    }
    context.ports().putAll(actual);
  }

  private Map<PortKey, Port> expectedPorts(
      final ValidationContext context
  ) {
    Map<PortKey, Port> result = new LinkedHashMap<>();
    for (NodeRef ref : context.byId().values()) {
      Node node = ref.node();
      if (node.type() == null) {
        continue;
      }
      if (node.type() != NodeType.INPUT) {
        putPort(result, node.id(), "in", PortDirection.INPUT);
      }
      if (node.type() == NodeType.OUTPUT) {
        continue;
      }
      if (node.type() == NodeType.CONDITION) {
        putPort(result, node.id(), "then", PortDirection.OUTPUT);
        putPort(result, node.id(), "else", PortDirection.OUTPUT);
      } else if (node.type() == NodeType.PARALLEL) {
        for (String branchId : branchIds(node)) {
          putPort(result, node.id(), branchId, PortDirection.OUTPUT);
        }
      } else {
        putPort(result, node.id(), "out", PortDirection.OUTPUT);
      }
    }
    return result;
  }

  private void putPort(
      final Map<PortKey, Port> target,
      final String nodeId,
      final String id,
      final PortDirection direction
  ) {
    target.put(
        new PortKey(nodeId, id),
        new Port(
            nodeId,
            id,
            direction,
            PortKind.CONTROL,
            null,
            true,
            false
        )
    );
  }

  private boolean samePortSemantics(
      final Port actual,
      final Port expected
  ) {
    return actual.direction() == expected.direction()
        && actual.kind() == expected.kind()
        && actual.required() == expected.required()
        && actual.multiple() == expected.multiple();
  }

  private void validateEdges(
      final List<Edge> source,
      final ValidationContext context,
      final DiagnosticCollector diagnostics
  ) {
    List<Edge> edges = source == null ? List.of() : source;
    if (edges.size() > 512) {
      diagnostics.error(
          "SKILL_DESIGNER_EDGE_LIMIT_EXCEEDED",
          "Skill Designer exceeds 512 edges",
          null,
          "edges",
          "/edges"
      );
    }
    Set<String> edgeIds = new HashSet<>();
    Map<EdgeSignature, EdgeRef> actual = new LinkedHashMap<>();
    for (int index = 0; index < edges.size(); index++) {
      Edge edge = edges.get(index);
      String pointer = "/edges/" + index;
      if (edge == null || trim(edge.id()) == null
          || trim(edge.sourceNodeId()) == null
          || trim(edge.sourceHandle()) == null
          || trim(edge.targetNodeId()) == null
          || trim(edge.targetHandle()) == null) {
        diagnostics.error(
            "SKILL_DESIGNER_EDGE_INVALID",
            "Skill Designer edge is incomplete",
            edge == null ? null : edge.sourceNodeId(),
            null,
            pointer
        );
        continue;
      }
      if (!edgeIds.add(edge.id())) {
        diagnostics.error(
            "SKILL_DESIGNER_EDGE_ID_DUPLICATE",
            "Duplicate Skill Designer edge ID: " + edge.id(),
            edge.sourceNodeId(),
            null,
            pointer + "/id"
        );
      }
      if (edge.sourceNodeId().equals(edge.targetNodeId())) {
        diagnostics.error(
            "SKILL_DESIGNER_EDGE_SELF_REFERENCE",
            "Skill Designer edge cannot connect a node to itself",
            edge.sourceNodeId(),
            null,
            pointer
        );
      }
      validateEdgePort(edge, true, index, context, diagnostics);
      validateEdgePort(edge, false, index, context, diagnostics);
      EdgeSignature signature = signature(edge);
      if (actual.putIfAbsent(signature, new EdgeRef(edge, index)) != null) {
        diagnostics.error(
            "SKILL_DESIGNER_EDGE_DUPLICATE",
            "Duplicate Skill Designer connection",
            edge.sourceNodeId(),
            null,
            pointer
        );
      }
    }
    Set<EdgeSignature> expected = expectedEdges(context);
    for (EdgeSignature signature : expected) {
      if (!actual.containsKey(signature)) {
        diagnostics.error(
            "SKILL_DESIGNER_EDGE_MISSING",
            "Required control-flow edge is missing",
            signature.sourceNodeId(),
            null,
            "/edges",
            Map.of(
                "sourceHandle", signature.sourceHandle(),
                "targetNodeId", signature.targetNodeId(),
                "targetHandle", signature.targetHandle()
            )
        );
      }
    }
    for (Map.Entry<EdgeSignature, EdgeRef> entry : actual.entrySet()) {
      if (!expected.contains(entry.getKey())) {
        diagnostics.error(
            "SKILL_DESIGNER_EDGE_UNEXPECTED",
            "Edge does not match structured workflow order",
            entry.getKey().sourceNodeId(),
            null,
            "/edges/" + entry.getValue().index()
        );
      }
    }
  }

  private void validateEdgePort(
      final Edge edge,
      final boolean source,
      final int edgeIndex,
      final ValidationContext context,
      final DiagnosticCollector diagnostics
  ) {
    String nodeId = source ? edge.sourceNodeId() : edge.targetNodeId();
    String portId = source ? edge.sourceHandle() : edge.targetHandle();
    PortRef port = context.ports().get(new PortKey(nodeId, portId));
    String field = source ? "sourceHandle" : "targetHandle";
    if (port == null) {
      diagnostics.error(
          "SKILL_DESIGNER_EDGE_PORT_UNKNOWN",
          "Edge references an unknown node port: " + nodeId + "/" + portId,
          nodeId,
          field,
          "/edges/" + edgeIndex + "/" + field
      );
      return;
    }
    PortDirection required = source
        ? PortDirection.OUTPUT : PortDirection.INPUT;
    if (port.port().direction() != required) {
      diagnostics.error(
          "SKILL_DESIGNER_EDGE_PORT_DIRECTION_INVALID",
          "Edge uses a port with the wrong direction",
          nodeId,
          field,
          "/edges/" + edgeIndex + "/" + field
      );
    }
  }

  private Set<EdgeSignature> expectedEdges(
      final ValidationContext context
  ) {
    Set<EdgeSignature> result = new LinkedHashSet<>();
    if (!canDeriveGraph(context)) {
      return result;
    }
    List<NodeRef> top = context.sequence(null, null);
    if (top.isEmpty()) {
      result.add(new EdgeSignature(
          SkillDesignerDocument.INPUT_NODE_ID,
          "out",
          SkillDesignerDocument.OUTPUT_NODE_ID,
          "in"
      ));
      return result;
    }
    result.add(new EdgeSignature(
        SkillDesignerDocument.INPUT_NODE_ID,
        "out",
        top.getFirst().node().id(),
        "in"
    ));
    for (int index = 0; index < top.size(); index++) {
      Node node = top.get(index).node();
      String successor = index + 1 < top.size()
          ? top.get(index + 1).node().id()
          : SkillDesignerDocument.OUTPUT_NODE_ID;
      addExpectedNodeEdges(node, successor, context, result);
    }
    return result;
  }

  private boolean canDeriveGraph(final ValidationContext context) {
    if (!context.byId().containsKey(SkillDesignerDocument.INPUT_NODE_ID)
        || !context.byId().containsKey(SkillDesignerDocument.OUTPUT_NODE_ID)) {
      return false;
    }
    for (List<NodeRef> sequence : context.sequences().values()) {
      for (NodeRef ref : sequence) {
        String id = trim(ref.node().id());
        if (id == null || context.byId().get(id) != ref) {
          return false;
        }
      }
    }
    return true;
  }

  private void addExpectedNodeEdges(
      final Node node,
      final String successor,
      final ValidationContext context,
      final Set<EdgeSignature> target
  ) {
    if (node.type() == NodeType.CONDITION
        || node.type() == NodeType.PARALLEL) {
      for (String branchId : branchIds(node)) {
        addExpectedBranchEdges(
            node.id(),
            branchId,
            successor,
            context,
            target
        );
      }
      return;
    }
    target.add(new EdgeSignature(node.id(), "out", successor, "in"));
  }

  private void addExpectedBranchEdges(
      final String parentNodeId,
      final String branchId,
      final String successor,
      final ValidationContext context,
      final Set<EdgeSignature> target
  ) {
    List<NodeRef> branch = context.sequence(parentNodeId, branchId);
    if (branch.isEmpty()) {
      target.add(new EdgeSignature(
          parentNodeId,
          branchId,
          successor,
          "in"
      ));
      return;
    }
    target.add(new EdgeSignature(
        parentNodeId,
        branchId,
        branch.getFirst().node().id(),
        "in"
    ));
    for (int index = 0; index < branch.size(); index++) {
      String destination = index + 1 < branch.size()
          ? branch.get(index + 1).node().id() : successor;
      target.add(new EdgeSignature(
          branch.get(index).node().id(),
          "out",
          destination,
          "in"
      ));
    }
  }

  private BindingAliases validateBindings(
      final SkillDesignerDocument document,
      final DiagnosticCollector diagnostics
  ) {
    Set<String> tools = validateBindingList(
        document.tools(),
        "tools",
        BindingType.TOOL,
        diagnostics
    );
    Set<String> prompts = validateBindingList(
        document.prompts(),
        "prompts",
        BindingType.PROMPT,
        diagnostics
    );
    Set<String> resources = validateBindingList(
        document.resources(),
        "resources",
        BindingType.RESOURCE,
        diagnostics
    );
    return new BindingAliases(tools, prompts, resources);
  }

  private Set<String> validateBindingList(
      final List<Map<String, Object>> source,
      final String field,
      final BindingType type,
      final DiagnosticCollector diagnostics
  ) {
    List<Map<String, Object>> bindings = source == null ? List.of() : source;
    Set<String> aliases = new LinkedHashSet<>();
    for (int index = 0; index < bindings.size(); index++) {
      Map<String, Object> binding = bindings.get(index);
      String pointer = "/" + field + "/" + index;
      if (binding == null) {
        diagnostics.error(
            "SKILL_DESIGNER_BINDING_INVALID",
            "MCP capability binding must be an object",
            null,
            field,
            pointer
        );
        continue;
      }
      Set<String> allowed = type == BindingType.RESOURCE
          ? Set.of("alias", "serverId", "snapshotId", "uri", "uriTemplate")
          : Set.of("alias", "name", "serverId", "snapshotId");
      binding.keySet().stream()
          .filter(key -> !allowed.contains(key))
          .forEach(key -> diagnostics.error(
              "SKILL_DESIGNER_BINDING_FIELD_UNSUPPORTED",
              "MCP capability binding contains an unsupported field: " + key,
              null,
              field + "." + key,
              pointer + "/" + escape(key)
          ));
      String alias = text(binding.get("alias"));
      if (alias == null || !ALIAS.matcher(alias).matches()) {
        diagnostics.error(
            "SKILL_DESIGNER_BINDING_ALIAS_INVALID",
            "MCP capability binding alias is missing or invalid",
            null,
            field + ".alias",
            pointer + "/alias"
        );
      } else if (!aliases.add(alias)) {
        diagnostics.error(
            "SKILL_DESIGNER_BINDING_ALIAS_DUPLICATE",
            "Duplicate MCP capability binding alias: " + alias,
            null,
            field + ".alias",
            pointer + "/alias"
        );
      }
      requireBindingText(binding, "serverId", field, pointer, diagnostics);
      requireBindingText(binding, "snapshotId", field, pointer, diagnostics);
      if (type == BindingType.RESOURCE) {
        boolean uri = text(binding.get("uri")) != null;
        boolean template = text(binding.get("uriTemplate")) != null;
        if (uri == template) {
          diagnostics.error(
              "SKILL_DESIGNER_BINDING_SELECTOR_INVALID",
              "Resource binding requires exactly one uri or uriTemplate",
              null,
              field,
              pointer
          );
        }
      } else {
        requireBindingText(binding, "name", field, pointer, diagnostics);
      }
    }
    return Set.copyOf(aliases);
  }

  private void requireBindingText(
      final Map<String, Object> binding,
      final String key,
      final String field,
      final String pointer,
      final DiagnosticCollector diagnostics
  ) {
    if (text(binding.get(key)) == null) {
      diagnostics.error(
          "SKILL_DESIGNER_BINDING_FIELD_REQUIRED",
          "MCP capability binding " + key + " is required",
          null,
          field + "." + key,
          pointer + "/" + key
      );
    }
  }

  private void validateNodeConfigurations(
      final ValidationContext context,
      final BindingAliases aliases,
      final DiagnosticCollector diagnostics
  ) {
    for (NodeRef ref : context.byId().values()) {
      Node node = ref.node();
      if (node.type() == null || node.configuration() == null) {
        continue;
      }
      String pointer = "/nodes/" + ref.index() + "/configuration";
      Set<String> allowed = CONFIGURATION_FIELDS.get(node.type());
      for (String key : node.configuration().keySet()) {
        if (!allowed.contains(key)) {
          diagnostics.error(
              "SKILL_DESIGNER_NODE_PROPERTY_UNSUPPORTED",
              "Node configuration contains an unsupported field: " + key,
              node.id(),
              "configuration." + key,
              pointer + "/" + escape(key)
          );
        }
      }
      switch (node.type()) {
        case TOOL -> validateCapabilityNode(
            node,
            "tool",
            aliases.tools(),
            pointer,
            diagnostics
        );
        case PROMPT -> validateCapabilityNode(
            node,
            "prompt",
            aliases.prompts(),
            pointer,
            diagnostics
        );
        case RESOURCE -> validateCapabilityNode(
            node,
            "resource",
            aliases.resources(),
            pointer,
            diagnostics
        );
        case CONDITION -> {
          if (!node.configuration().containsKey("condition")) {
            diagnostics.error(
                "SKILL_DESIGNER_CONDITION_REQUIRED",
                "Condition node must define condition",
                node.id(),
                "configuration.condition",
                pointer + "/condition"
            );
          } else {
            validateConditionGrammar(
                node.configuration().get("condition"),
                node.id(),
                "configuration.condition",
                pointer + "/condition",
                0,
                diagnostics
            );
          }
        }
        case PARALLEL -> validateParallel(
            node,
            ref,
            context,
            diagnostics
        );
        default -> {
          // Virtual nodes have no required configuration.
        }
      }
    }
  }

  private void validateCapabilityNode(
      final Node node,
      final String field,
      final Set<String> aliases,
      final String pointer,
      final DiagnosticCollector diagnostics
  ) {
    String alias = text(node.configuration().get(field));
    if (alias == null) {
      diagnostics.error(
          "SKILL_DESIGNER_CAPABILITY_ALIAS_REQUIRED",
          "MCP node requires a " + field + " alias",
          node.id(),
          "configuration." + field,
          pointer + "/" + field
      );
    } else if (!aliases.contains(alias)) {
      diagnostics.error(
          "SKILL_DESIGNER_CAPABILITY_UNBOUND",
          "MCP node references an unbound " + field + " alias: " + alias,
          node.id(),
          "configuration." + field,
          pointer + "/" + field,
          Map.of("alias", alias)
      );
    }
    if (("tool".equals(field) || "prompt".equals(field))
        && node.configuration().get("arguments") != null
        && !(node.configuration().get("arguments") instanceof Map<?, ?>)) {
      diagnostics.error(
          "SKILL_DESIGNER_ARGUMENTS_INVALID",
          "MCP node arguments must be an object",
          node.id(),
          "configuration.arguments",
          pointer + "/arguments"
      );
    }
  }

  private void validateParallel(
      final Node node,
      final NodeRef ref,
      final ValidationContext context,
      final DiagnosticCollector diagnostics
  ) {
    Object value = node.configuration().get("branches");
    if (!(value instanceof List<?> branches)) {
      diagnostics.error(
          "SKILL_DESIGNER_PARALLEL_BRANCHES_REQUIRED",
          "Parallel node branches must be an array",
          node.id(),
          "configuration.branches",
          "/nodes/" + ref.index() + "/configuration/branches"
      );
      return;
    }
    if (branches.size() < 2 || branches.size() > MAXIMUM_PARALLEL_BRANCHES) {
      diagnostics.error(
          "SKILL_DESIGNER_PARALLEL_BRANCH_COUNT_INVALID",
          "Parallel node must contain between 2 and 16 branches",
          node.id(),
          "configuration.branches",
          "/nodes/" + ref.index() + "/configuration/branches"
      );
    }
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < branches.size(); index++) {
      final int branchIndex = index;
      Object branch = branches.get(index);
      String id = branch instanceof Map<?, ?> map
          ? text(map.get("id")) : null;
      if (branch instanceof Map<?, ?> map) {
        map.keySet().stream()
            .map(String::valueOf)
            .filter(key -> !"id".equals(key))
            .forEach(key -> diagnostics.error(
                "SKILL_DESIGNER_PARALLEL_BRANCH_FIELD_UNSUPPORTED",
                "Parallel branch contains an unsupported field: " + key,
                node.id(),
                "configuration.branches[" + branchIndex + "]." + key,
                "/nodes/" + ref.index()
                    + "/configuration/branches/" + branchIndex
                    + "/" + escape(key)
            ));
      }
      if (id == null || !ALIAS.matcher(id).matches()) {
        diagnostics.error(
            "SKILL_DESIGNER_PARALLEL_BRANCH_ID_INVALID",
            "Parallel branch ID is missing or invalid",
            node.id(),
            "configuration.branches[" + index + "].id",
            "/nodes/" + ref.index()
                + "/configuration/branches/" + index + "/id"
        );
      } else if (!ids.add(id)) {
        diagnostics.error(
            "SKILL_DESIGNER_PARALLEL_BRANCH_ID_DUPLICATE",
            "Duplicate parallel branch ID: " + id,
            node.id(),
            "configuration.branches[" + index + "].id",
            "/nodes/" + ref.index()
                + "/configuration/branches/" + index + "/id"
        );
      }
      if (id != null && context.sequence(node.id(), id).stream()
          .map(NodeRef::node)
          .map(Node::type)
          .noneMatch(SkillDesignerValidator::isMcpNode)) {
        diagnostics.error(
            "SKILL_DESIGNER_PARALLEL_BRANCH_EMPTY",
            "Parallel branch must contain an executable MCP node",
            node.id(),
            "configuration.branches[" + index + "]",
            "/nodes/" + ref.index()
                + "/configuration/branches/" + index
        );
      }
    }
  }

  private void validateConditionGrammar(
      final Object condition,
      final String nodeId,
      final String fieldPath,
      final String pointer,
      final int depth,
      final DiagnosticCollector diagnostics
  ) {
    if (depth > 8) {
      diagnostics.error(
          "SKILL_DESIGNER_CONDITION_DEPTH_EXCEEDED",
          "Workflow condition exceeds maximum nesting depth",
          nodeId,
          fieldPath,
          pointer
      );
      return;
    }
    if (!(condition instanceof Map<?, ?> map) || map.size() != 1) {
      diagnostics.error(
          "SKILL_DESIGNER_CONDITION_INVALID",
          "Condition must contain exactly one boolean operator",
          nodeId,
          fieldPath,
          pointer
      );
      return;
    }
    Map.Entry<?, ?> entry = map.entrySet().iterator().next();
    String operator = entry.getKey() instanceof String key ? key : null;
    Object value = entry.getValue();
    if (operator == null || !Set.of(
        "all",
        "any",
        "equals",
        "isTrue",
        "not",
        "notEquals"
    ).contains(operator)) {
      diagnostics.error(
          "SKILL_DESIGNER_CONDITION_OPERATOR_UNSUPPORTED",
          "Condition contains an unsupported operator: " + operator,
          nodeId,
          fieldPath,
          pointer
      );
      return;
    }
    if (("equals".equals(operator) || "notEquals".equals(operator))
        && (!(value instanceof List<?> list) || list.size() != 2)) {
      diagnostics.error(
          "SKILL_DESIGNER_CONDITION_OPERANDS_INVALID",
          "Condition comparison must contain exactly two operands",
          nodeId,
          fieldPath + "." + operator,
          pointer + "/" + operator
      );
    } else if ("all".equals(operator) || "any".equals(operator)) {
      if (!(value instanceof List<?> list)
          || list.isEmpty() || list.size() > 16) {
        diagnostics.error(
            "SKILL_DESIGNER_CONDITION_GROUP_INVALID",
            "Condition group must contain between 1 and 16 conditions",
            nodeId,
            fieldPath + "." + operator,
            pointer + "/" + operator
        );
      } else {
        for (int index = 0; index < list.size(); index++) {
          validateConditionGrammar(
              list.get(index),
              nodeId,
              fieldPath + "." + operator + "[" + index + "]",
              pointer + "/" + operator + "/" + index,
              depth + 1,
              diagnostics
          );
        }
      }
    } else if ("not".equals(operator)) {
      validateConditionGrammar(
          value,
          nodeId,
          fieldPath + ".not",
          pointer + "/not",
          depth + 1,
          diagnostics
      );
    }
  }

  private void validateReferences(
      final SkillDesignerDocument document,
      final ValidationContext context,
      final DiagnosticCollector diagnostics
  ) {
    Set<String> available = new LinkedHashSet<>();
    validateSequenceReferences(
        context.sequence(null, null),
        available,
        context,
        diagnostics
    );
    if (document.workflowOutput() != null) {
      validateReferenceTree(
          document.workflowOutput(),
          available,
          null,
          "workflowOutput",
          "/workflowOutput",
          0,
          diagnostics
      );
    }
  }

  private void validateSequenceReferences(
      final List<NodeRef> sequence,
      final Set<String> available,
      final ValidationContext context,
      final DiagnosticCollector diagnostics
  ) {
    for (NodeRef ref : sequence) {
      Node node = ref.node();
      validateReferenceTree(
          node.configuration(),
          available,
          node.id(),
          "configuration",
          "/nodes/" + ref.index() + "/configuration",
          0,
          diagnostics
      );
      if (node.type() == NodeType.CONDITION
          || node.type() == NodeType.PARALLEL) {
        Set<String> branchBase = new LinkedHashSet<>(available);
        Set<String> completedBranches = new LinkedHashSet<>();
        for (String branchId : branchIds(node)) {
          Set<String> branchAvailable = new LinkedHashSet<>(branchBase);
          List<NodeRef> branch = context.sequence(node.id(), branchId);
          validateSequenceReferences(
              branch,
              branchAvailable,
              context,
              diagnostics
          );
          branch.forEach(child -> completedBranches.add(child.node().id()));
        }
        available.addAll(completedBranches);
      }
      available.add(node.id());
    }
  }

  private void validateReferenceTree(
      final Object value,
      final Set<String> available,
      final String nodeId,
      final String fieldPath,
      final String pointer,
      final int depth,
      final DiagnosticCollector diagnostics
  ) {
    if (depth > 32) {
      diagnostics.error(
          "SKILL_DESIGNER_REFERENCE_DEPTH_EXCEEDED",
          "Workflow template exceeds maximum JSON depth",
          nodeId,
          fieldPath,
          pointer
      );
      return;
    }
    if (value instanceof Map<?, ?> map) {
      if (map.containsKey("$ref")) {
        Object raw = map.get("$ref");
        if (map.size() != 1 || !(raw instanceof String reference)) {
          diagnostics.error(
              "SKILL_DESIGNER_REFERENCE_INVALID",
              "$ref object must contain exactly one string field",
              nodeId,
              fieldPath,
              pointer
          );
        } else {
          validateReference(
              reference,
              available,
              nodeId,
              fieldPath,
              pointer + "/$ref",
              diagnostics
          );
        }
        return;
      }
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        validateReferenceTree(
            entry.getValue(),
            available,
            nodeId,
            fieldPath + "." + key,
            pointer + "/" + escape(key),
            depth + 1,
            diagnostics
        );
      }
    } else if (value instanceof List<?> list) {
      for (int index = 0; index < list.size(); index++) {
        validateReferenceTree(
            list.get(index),
            available,
            nodeId,
            fieldPath + "[" + index + "]",
            pointer + "/" + index,
            depth + 1,
            diagnostics
        );
      }
    }
  }

  private void validateReference(
      final String reference,
      final Set<String> available,
      final String nodeId,
      final String fieldPath,
      final String pointer,
      final DiagnosticCollector diagnostics
  ) {
    String[] parts = reference == null
        ? new String[0] : reference.trim().split("\\.", -1);
    boolean formatValid = reference != null
        && !reference.isBlank() && reference.length() <= 512
        && parts.length > 0;
    for (String part : parts) {
      formatValid &= REFERENCE_SEGMENT.matcher(part).matches();
    }
    if (!formatValid) {
      diagnostics.error(
          "SKILL_DESIGNER_REFERENCE_INVALID",
          "Workflow reference has an invalid format: " + reference,
          nodeId,
          fieldPath,
          pointer
      );
      return;
    }
    if ("input".equals(parts[0])) {
      return;
    }
    if (!"steps".equals(parts[0]) || parts.length < 2
        || !available.contains(parts[1])) {
      diagnostics.error(
          "SKILL_DESIGNER_REFERENCE_NOT_AVAILABLE",
          "Referenced workflow data is not available before this node: "
              + reference,
          nodeId,
          fieldPath,
          pointer,
          Map.of("reference", reference)
      );
    }
  }

  private void validateTests(
      final List<TestCase> source,
      final ValidationContext context,
      final DiagnosticCollector diagnostics
  ) {
    List<TestCase> tests = source == null ? List.of() : source;
    if (tests.size() > 64) {
      diagnostics.error(
          "SKILL_DESIGNER_TEST_LIMIT_EXCEEDED",
          "Skill Designer exceeds 64 test cases",
          null,
          "tests",
          "/tests"
      );
    }
    Set<String> testIds = new HashSet<>();
    for (int index = 0; index < tests.size(); index++) {
      TestCase test = tests.get(index);
      String pointer = "/tests/" + index;
      if (test == null) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_INVALID",
            "Skill Designer test case must be an object",
            null,
            null,
            pointer
        );
        continue;
      }
      String id = text(test.id());
      if (id == null || !ALIAS.matcher(id).matches()) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_ID_INVALID",
            "Test case ID is missing or invalid",
            null,
            "tests.id",
            pointer + "/id"
        );
      } else if (!testIds.add(id)) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_ID_DUPLICATE",
            "Duplicate test case ID: " + id,
            null,
            "tests.id",
            pointer + "/id"
        );
      }
      if (text(test.name()) == null) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_NAME_REQUIRED",
            "Test case name is required",
            null,
            "tests.name",
            pointer + "/name"
        );
      }
      if (test.input() == null || test.mocks() == null
          || test.assertions() == null) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_INVALID",
            "Test input, mocks and assertions are required",
            null,
            "tests",
            pointer
        );
        continue;
      }
      if (test.mocks().size() > 128 || test.assertions().size() > 128) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_ITEM_LIMIT_EXCEEDED",
            "Test case exceeds 128 mocks or assertions",
            null,
            "tests",
            pointer
        );
      }
      validateMocks(test.mocks(), pointer, context, diagnostics);
      validateAssertions(test.assertions(), pointer, context, diagnostics);
    }
  }

  private void validateMocks(
      final List<NodeMock> mocks,
      final String testPointer,
      final ValidationContext context,
      final DiagnosticCollector diagnostics
  ) {
    Set<String> targets = new HashSet<>();
    for (int index = 0; index < mocks.size(); index++) {
      NodeMock mock = mocks.get(index);
      String pointer = testPointer + "/mocks/" + index;
      if (mock == null || mock.mode() == null
          || text(mock.nodeId()) == null) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_MOCK_INVALID",
            "Test case mock is incomplete",
            mock == null ? null : mock.nodeId(),
            "tests.mocks",
            pointer
        );
        continue;
      }
      NodeRef target = context.byId().get(mock.nodeId());
      if (target == null || !isMcpNode(target.node().type())) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_MOCK_TARGET_INVALID",
            "Test case mock must target an MCP node",
            mock.nodeId(),
            "tests.mocks.nodeId",
            pointer + "/nodeId"
        );
      }
      if (!targets.add(mock.nodeId())) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_MOCK_DUPLICATE",
            "Test case contains duplicate mock target: " + mock.nodeId(),
            mock.nodeId(),
            "tests.mocks.nodeId",
            pointer + "/nodeId"
        );
      }
      if (mock.mode() == MockMode.ERROR && text(mock.errorCode()) == null) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_MOCK_ERROR_CODE_REQUIRED",
            "Error mock must define errorCode",
            mock.nodeId(),
            "tests.mocks.errorCode",
            pointer + "/errorCode"
        );
      }
    }
  }

  private void validateAssertions(
      final List<TestAssertion> assertions,
      final String testPointer,
      final ValidationContext context,
      final DiagnosticCollector diagnostics
  ) {
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < assertions.size(); index++) {
      TestAssertion assertion = assertions.get(index);
      String pointer = testPointer + "/assertions/" + index;
      if (assertion == null || assertion.operator() == null) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_ASSERTION_INVALID",
            "Test assertion is incomplete",
            null,
            "tests.assertions",
            pointer
        );
        continue;
      }
      String id = text(assertion.id());
      if (id == null || !ALIAS.matcher(id).matches()) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_ASSERTION_ID_INVALID",
            "Test assertion ID is missing or invalid",
            assertion.sourceNodeId(),
            "tests.assertions.id",
            pointer + "/id"
        );
      } else if (!ids.add(id)) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_ASSERTION_ID_DUPLICATE",
            "Duplicate test assertion ID: " + id,
            assertion.sourceNodeId(),
            "tests.assertions.id",
            pointer + "/id"
        );
      }
      if (!context.byId().containsKey(assertion.sourceNodeId())) {
        diagnostics.error(
            "SKILL_DESIGNER_TEST_ASSERTION_SOURCE_INVALID",
            "Test assertion references an unknown node",
            assertion.sourceNodeId(),
            "tests.assertions.sourceNodeId",
            pointer + "/sourceNodeId"
        );
      }
    }
  }

  private void validateSchemas(
      final SkillDesignerDocument document,
      final DiagnosticCollector diagnostics
  ) {
    validateSchema(
        document.inputSchema(),
        "SKILL_DESIGNER_INPUT_SCHEMA_INVALID",
        "Skill inputSchema",
        "inputSchema",
        "/inputSchema",
        diagnostics
    );
    validateSchema(
        document.outputSchema(),
        "SKILL_DESIGNER_OUTPUT_SCHEMA_INVALID",
        "Skill outputSchema",
        "outputSchema",
        "/outputSchema",
        diagnostics
    );
  }

  private void validateSchema(
      final Map<String, Object> schema,
      final String code,
      final String label,
      final String field,
      final String pointer,
      final DiagnosticCollector diagnostics
  ) {
    if (schema == null) {
      return;
    }
    if (!"object".equals(schema.get("type"))) {
      diagnostics.error(
          code,
          label + " root type must be object",
          null,
          field + ".type",
          pointer + "/type"
      );
    }
    try {
      schemaValidator.validateSchema(schema, label);
    } catch (IllegalArgumentException exception) {
      diagnostics.error(
          code,
          safeMessage(exception, label + " is invalid"),
          null,
          field,
          pointer
      );
    }
  }

  private void requireMap(
      final Map<String, Object> value,
      final String field,
      final String pointer,
      final DiagnosticCollector diagnostics
  ) {
    if (value == null) {
      diagnostics.error(
          "SKILL_DESIGNER_FIELD_REQUIRED",
          "Skill Designer " + field + " is required",
          null,
          field,
          pointer
      );
    }
  }

  private Set<String> branchIds(final Node node) {
    if (node.type() == NodeType.CONDITION) {
      return Set.of("then", "else");
    }
    if (node.type() != NodeType.PARALLEL
        || node.configuration() == null
        || !(node.configuration().get("branches") instanceof List<?> list)) {
      return Set.of();
    }
    Set<String> result = new LinkedHashSet<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map && text(map.get("id")) != null) {
        result.add(text(map.get("id")));
      }
    }
    return result;
  }

  private static List<NodeRef> ordered(final List<NodeRef> source) {
    return source.stream()
        .sorted(Comparator.comparingInt(ref -> ref.node().order()))
        .toList();
  }

  private static EdgeSignature signature(final Edge edge) {
    return new EdgeSignature(
        edge.sourceNodeId(),
        edge.sourceHandle(),
        edge.targetNodeId(),
        edge.targetHandle()
    );
  }

  private static boolean isVirtualId(final String value) {
    return SkillDesignerDocument.INPUT_NODE_ID.equals(value)
        || SkillDesignerDocument.OUTPUT_NODE_ID.equals(value);
  }

  private static boolean isMcpNode(final NodeType type) {
    return type == NodeType.TOOL
        || type == NodeType.PROMPT
        || type == NodeType.RESOURCE;
  }

  private static String text(final Object value) {
    return value instanceof String text && !text.isBlank()
        ? text.trim() : null;
  }

  private static String trim(final String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String escape(final String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  private static String safeMessage(
      final RuntimeException exception,
      final String fallback
  ) {
    return exception.getMessage() == null || exception.getMessage().isBlank()
        ? fallback : exception.getMessage();
  }

  private enum BindingType {
    TOOL,
    PROMPT,
    RESOURCE
  }

  private record BindingAliases(
      Set<String> tools,
      Set<String> prompts,
      Set<String> resources
  ) {
  }

  private record NodeRef(Node node, int index) {
  }

  private record SequenceKey(String parentNodeId, String branchId) {
  }

  private record PortKey(String nodeId, String id) {
  }

  private record PortRef(Port port, int index) {
  }

  private record EdgeRef(Edge edge, int index) {
  }

  private record EdgeSignature(
      String sourceNodeId,
      String sourceHandle,
      String targetNodeId,
      String targetHandle
  ) {
  }

  private static final class ValidationContext {

    private final Map<String, NodeRef> byId;

    private final Map<SequenceKey, List<NodeRef>> sequences;

    private final Map<PortKey, PortRef> ports = new HashMap<>();

    private ValidationContext(
        final Map<String, NodeRef> byId,
        final Map<SequenceKey, List<NodeRef>> sequences
    ) {
      this.byId = byId;
      this.sequences = sequences;
    }

    private Map<String, NodeRef> byId() {
      return byId;
    }

    private Map<SequenceKey, List<NodeRef>> sequences() {
      return sequences;
    }

    private Map<PortKey, PortRef> ports() {
      return ports;
    }

    private List<NodeRef> sequence(
        final String parentNodeId,
        final String branchId
    ) {
      return ordered(sequences.getOrDefault(
          new SequenceKey(parentNodeId, branchId),
          List.of()
      ));
    }
  }

  private static final class DiagnosticCollector {

    private final List<SkillDesignerDiagnostic> diagnostics =
        new ArrayList<>();

    private boolean truncated;

    private void error(
        final String code,
        final String message,
        final String nodeId,
        final String fieldPath,
        final String jsonPointer
    ) {
      error(code, message, nodeId, fieldPath, jsonPointer, Map.of());
    }

    private void error(
        final String code,
        final String message,
        final String nodeId,
        final String fieldPath,
        final String jsonPointer,
        final Map<String, Object> details
    ) {
      if (diagnostics.size() >= MAXIMUM_DIAGNOSTICS - 1) {
        truncated = true;
        return;
      }
      diagnostics.add(new SkillDesignerDiagnostic(
          Severity.ERROR,
          code,
          message,
          nodeId,
          fieldPath,
          jsonPointer,
          details
      ));
    }

    private List<SkillDesignerDiagnostic> result() {
      if (truncated) {
        diagnostics.add(new SkillDesignerDiagnostic(
            Severity.WARNING,
            "SKILL_DESIGNER_DIAGNOSTICS_TRUNCATED",
            "Additional Skill Designer diagnostics were truncated",
            null,
            null,
            null,
            Map.of("maximumDiagnostics", MAXIMUM_DIAGNOSTICS)
        ));
      }
      return List.copyOf(diagnostics);
    }
  }
}
