package org.simplepoint.plugin.ai.skill.api.designer;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable design-time representation of one Skill workflow.
 *
 * <p>The document contains editor layout information and must be compiled into
 * an immutable Skill Manifest before execution or publication.
 *
 * @param schemaVersion designer document contract version
 * @param metadata Skill manifest metadata retained across round trips
 * @param inputSchema Skill input JSON Schema
 * @param outputSchema Skill output JSON Schema
 * @param tools pinned MCP Tool bindings
 * @param prompts pinned MCP Prompt bindings
 * @param resources pinned MCP Resource bindings
 * @param nodes workflow and virtual input/output nodes
 * @param ports normalized node connection ports
 * @param edges normalized visual control-flow edges
 * @param workflowOutput declarative workflow output template
 * @param budgets optional execution budget declaration
 * @param approvals optional execution approval declaration
 * @param tests design-time test cases and MCP mocks
 * @param viewport optional editor viewport
 */
@Schema(title = "Skill Designer Document")
public record SkillDesignerDocument(
    String schemaVersion,
    Map<String, Object> metadata,
    Map<String, Object> inputSchema,
    Map<String, Object> outputSchema,
    List<Map<String, Object>> tools,
    List<Map<String, Object>> prompts,
    List<Map<String, Object>> resources,
    List<Node> nodes,
    List<Port> ports,
    List<Edge> edges,
    Object workflowOutput,
    Map<String, Object> budgets,
    Map<String, Object> approvals,
    List<TestCase> tests,
    Viewport viewport
) {

  /** Current designer document version. */
  public static final String SCHEMA_VERSION =
      "simplepoint.io/designer/v1alpha1";

  /** Reserved virtual input node ID. */
  public static final String INPUT_NODE_ID = "__input";

  /** Reserved virtual output node ID. */
  public static final String OUTPUT_NODE_ID = "__output";

  /** Creates a defensively copied designer document. */
  public SkillDesignerDocument {
    metadata = copyMap(metadata);
    inputSchema = copyMap(inputSchema);
    outputSchema = copyMap(outputSchema);
    tools = copyMaps(tools);
    prompts = copyMaps(prompts);
    resources = copyMaps(resources);
    nodes = copyList(nodes);
    ports = copyList(ports);
    edges = copyList(edges);
    budgets = copyMap(budgets);
    approvals = copyMap(approvals);
    tests = copyList(tests);
  }

  /** Node types supported by the v1alpha1 Skill designer. */
  public enum NodeType {
    INPUT,
    OUTPUT,
    TOOL,
    PROMPT,
    RESOURCE,
    CONDITION,
    PARALLEL
  }

  /**
   * One designer node.
   *
   * @param id stable node and manifest step identifier
   * @param type designer node type
   * @param parentNodeId parent condition/parallel node, or null at top level
   * @param branchId parent branch identifier, or null at top level
   * @param order zero-based order inside the parent branch
   * @param configuration type-specific fields excluding id and type
   * @param position editor position
   */
  public record Node(
      String id,
      NodeType type,
      String parentNodeId,
      String branchId,
      int order,
      Map<String, Object> configuration,
      Position position
  ) {

    /** Creates a defensively copied node. */
    public Node {
      configuration = copyMap(configuration);
    }
  }

  /** Direction of a designer node connection port. */
  public enum PortDirection {
    INPUT,
    OUTPUT
  }

  /** Semantic payload carried by a designer port. */
  public enum PortKind {
    CONTROL,
    DATA
  }

  /**
   * Stable connection point exposed by one node.
   *
   * @param nodeId owning node ID
   * @param id port ID used by edge handles
   * @param direction input or output direction
   * @param kind control-flow or data port
   * @param label optional display label
   * @param required whether the port must be connected
   * @param multiple whether more than one edge may use the port
   */
  public record Port(
      String nodeId,
      String id,
      PortDirection direction,
      PortKind kind,
      String label,
      boolean required,
      boolean multiple
  ) {
  }

  /**
   * One normalized visual control-flow edge.
   *
   * @param id stable edge identifier
   * @param sourceNodeId source node ID
   * @param sourceHandle optional source handle such as then or a branch ID
   * @param targetNodeId target node ID
   * @param targetHandle optional target handle
   */
  public record Edge(
      String id,
      String sourceNodeId,
      String sourceHandle,
      String targetNodeId,
      String targetHandle
  ) {
  }

  /** Result mode for a mocked design-time node execution. */
  public enum MockMode {
    SUCCESS,
    ERROR
  }

  /** Test assertion operator supported by the first designer contract. */
  public enum AssertionOperator {
    EQUALS,
    NOT_EQUALS,
    EXISTS,
    NOT_EXISTS,
    CONTAINS,
    MATCHES
  }

  /**
   * Fixed design-time result for a workflow node.
   *
   * @param nodeId target executable node ID
   * @param mode successful result or simulated failure
   * @param output mocked structured output for SUCCESS
   * @param errorCode stable simulated error code for ERROR
   * @param errorMessage simulated error message for ERROR
   */
  public record NodeMock(
      String nodeId,
      MockMode mode,
      Object output,
      String errorCode,
      String errorMessage
  ) {
  }

  /**
   * One output assertion evaluated after a design-time test execution.
   *
   * @param id stable assertion ID
   * @param sourceNodeId source node ID, or {@code __output}
   * @param fieldPath dot-separated structured output path
   * @param operator assertion operator
   * @param expected expected value when required by the operator
   */
  public record TestAssertion(
      String id,
      String sourceNodeId,
      String fieldPath,
      AssertionOperator operator,
      Object expected
  ) {
  }

  /**
   * One reusable design-time test case.
   *
   * @param id stable test case ID
   * @param name display name
   * @param enabled whether the case participates in a test run
   * @param input Skill input value
   * @param mocks fixed node results used by Mock mode
   * @param assertions post-execution assertions
   */
  public record TestCase(
      String id,
      String name,
      boolean enabled,
      Map<String, Object> input,
      List<NodeMock> mocks,
      List<TestAssertion> assertions
  ) {

    /** Creates a defensively copied test case. */
    public TestCase {
      input = copyMap(input);
      mocks = copyList(mocks);
      assertions = copyList(assertions);
    }
  }

  /** Editor-space position. */
  public record Position(double x, double y) {
  }

  /** Persisted editor viewport. */
  public record Viewport(double x, double y, double zoom) {
  }

  private static Map<String, Object> copyMap(
      final Map<String, Object> source
  ) {
    if (source == null) {
      return null;
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }

  private static List<Map<String, Object>> copyMaps(
      final List<Map<String, Object>> source
  ) {
    if (source == null) {
      return null;
    }
    List<Map<String, Object>> result = new ArrayList<>(source.size());
    source.forEach(value -> result.add(copyMap(value)));
    return Collections.unmodifiableList(result);
  }

  private static <T> List<T> copyList(final List<T> source) {
    return source == null
        ? null : Collections.unmodifiableList(new ArrayList<>(source));
  }
}
