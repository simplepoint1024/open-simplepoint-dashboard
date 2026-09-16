package org.simplepoint.plugin.ai.skill.service.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerCompilationResult;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDiagnostic;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDiagnostic.Severity;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Edge;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.MockMode;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.NodeMock;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.NodeType;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestCase;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowConditionEvaluator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;

class SkillDesignerManifestAdapterTest {

  private final SkillDesignerManifestAdapter adapter =
      new SkillDesignerManifestAdapter();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void roundTripsCompleteV1Alpha1Manifest() {
    Map<String, Object> manifest = completeManifest();

    SkillDesignerDocument document = adapter.fromManifest(manifest);
    Map<String, Object> exported = adapter.toManifest(document);

    assertThat(exported).isEqualTo(manifest);
    assertThat(document.schemaVersion()).isEqualTo(
        SkillDesignerDocument.SCHEMA_VERSION
    );
    assertThat(document.nodes()).hasSize(9);
    assertThat(document.nodes())
        .filteredOn(node -> node.type() == NodeType.CONDITION)
        .singleElement()
        .extracting(SkillDesignerDocument.Node::id)
        .isEqualTo("route");
    assertThat(document.nodes())
        .filteredOn(node -> "route".equals(node.parentNodeId()))
        .extracting(SkillDesignerDocument.Node::branchId)
        .containsExactlyInAnyOrder("then", "else");
    assertThat(document.ports())
        .anySatisfy(port -> {
          assertThat(port.nodeId()).isEqualTo("route");
          assertThat(port.id()).isEqualTo("then");
        });
    assertThat(document.edges())
        .allSatisfy(edge -> assertThat(edge.targetHandle()).isEqualTo("in"));
    assertThat(document.tests()).isEmpty();
  }

  @Test
  void validatesDesignTimeTestCasesAgainstMcpNodes() {
    SkillDesignerDocument source = adapter.fromManifest(completeManifest());
    TestCase test = new TestCase(
        "happy-path",
        "Happy path",
        true,
        Map.of("text", "hello"),
        List.of(new NodeMock(
            "prepare",
            MockMode.SUCCESS,
            Map.of("text", "hello"),
            null,
            null
        )),
        List.of()
    );
    SkillDesignerDocument valid = copyWithTests(source, List.of(test));

    assertThat(adapter.toManifest(valid)).isEqualTo(completeManifest());

    TestCase invalidTest = new TestCase(
        "invalid",
        "Invalid target",
        true,
        Map.of(),
        List.of(new NodeMock(
            "route",
            MockMode.SUCCESS,
            Map.of(),
            null,
            null
        )),
        List.of()
    );
    SkillDesignerCompilationResult invalid = compiler().compile(
        copyWithTests(source, List.of(invalidTest))
    );
    assertThat(invalid.valid()).isFalse();
    assertThat(invalid.diagnostics()).singleElement()
        .satisfies(diagnostic -> assertThat(diagnostic.message())
            .contains("mock must target an MCP node"));
  }

  @Test
  void rejectsVisualEdgesThatDivergeFromStructuredWorkflow() {
    SkillDesignerDocument imported = adapter.fromManifest(completeManifest());
    List<Edge> edges = new ArrayList<>(imported.edges());
    edges.removeLast();
    SkillDesignerDocument inconsistent = copyWithEdges(imported, edges);

    assertThatThrownBy(() -> adapter.toManifest(inconsistent))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("edges do not match");
  }

  @Test
  void rejectsNestedControlNodesUnsupportedByManifestV1Alpha1() {
    Map<String, Object> manifest = completeManifest();
    Map<String, Object> spec = map(manifest.get("spec"));
    Map<String, Object> workflow = map(spec.get("workflow"));
    workflow.put("steps", List.of(Map.of(
        "id", "outer",
        "type", "condition",
        "condition", Map.of("isTrue", true),
        "then", List.of(Map.of(
            "id", "nested",
            "type", "parallel",
            "branches", List.of()
        ))
    )));

    assertThatThrownBy(() -> adapter.fromManifest(manifest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Nested workflow control node");
  }

  @Test
  void publishesSerializableDesignerAndDiagnosticContracts() throws Exception {
    SkillDesignerDocument document = adapter.fromManifest(completeManifest());
    SkillDesignerDiagnostic diagnostic = new SkillDesignerDiagnostic(
        Severity.WARNING,
        "SKILL_SCHEMA_WEAK_OUTPUT",
        "Tool output has no declared Schema",
        "prepare",
        "arguments.text",
        "/nodes/1/configuration/arguments/text",
        Map.of("capabilityAlias", "echo")
    );
    SkillDesignerCompilationResult result =
        new SkillDesignerCompilationResult(
            true,
            adapter.toManifest(document),
            "sha256:designer",
            List.of(diagnostic)
        );

    JsonNode documentJson = objectMapper.valueToTree(document);
    JsonNode resultJson = objectMapper.valueToTree(result);

    assertThat(documentJson.path("schemaVersion").asText()).isEqualTo(
        "simplepoint.io/designer/v1alpha1"
    );
    assertThat(documentJson.path("nodes").get(1).path("type").asText())
        .isEqualTo("TOOL");
    assertThat(resultJson.path("diagnostics").get(0).path("severity").asText())
        .isEqualTo("WARNING");

    try (InputStream input = getClass().getClassLoader().getResourceAsStream(
        "META-INF/simplepoint/schemas/"
            + "skill-designer-document-v1alpha1.schema.json"
    )) {
      assertThat(input).isNotNull();
      JsonNode schema = objectMapper.readTree(input);
      assertThat(schema.path("$id").asText()).endsWith(
          "skill-designer-document-v1alpha1.schema.json"
      );
      assertThat(schema.path("properties").path("schemaVersion")
          .path("const").asText()).isEqualTo(
          SkillDesignerDocument.SCHEMA_VERSION
      );
      assertThat(schema.path("properties").has("ports")).isTrue();
      assertThat(schema.path("properties").has("tests")).isTrue();
    }
  }

  @Test
  void compilesDesignerWithRuntimeWorkflowGrammarAndCanonicalHash() {
    SkillDesignerCompiler compiler = compiler();
    SkillDesignerDocument document = adapter.fromManifest(completeManifest());

    SkillDesignerCompilationResult first = compiler.compile(document);
    SkillDesignerCompilationResult second = compiler.compile(document);

    assertThat(first.valid()).isTrue();
    assertThat(first.manifest()).isEqualTo(completeManifest());
    assertThat(first.contentHash())
        .hasSize(64)
        .isEqualTo(second.contentHash());
    assertThat(first.diagnostics()).isEmpty();
  }

  @Test
  void returnsStructuredDiagnosticForInvalidDesignerGraph() {
    SkillDesignerDocument imported = adapter.fromManifest(completeManifest());
    SkillDesignerDocument inconsistent = copyWithEdges(imported, List.of());

    SkillDesignerCompilationResult result = compiler().compile(inconsistent);

    assertThat(result.valid()).isFalse();
    assertThat(result.manifest()).isNull();
    assertThat(result.diagnostics())
        .isNotEmpty()
        .allSatisfy(diagnostic -> assertThat(diagnostic.severity())
            .isEqualTo(Severity.ERROR))
        .extracting(SkillDesignerDiagnostic::code)
        .containsOnly("SKILL_DESIGNER_EDGE_MISSING");
  }

  private static SkillDesignerDocument copyWithEdges(
      final SkillDesignerDocument source,
      final List<Edge> edges
  ) {
    return new SkillDesignerDocument(
        source.schemaVersion(),
        source.metadata(),
        source.inputSchema(),
        source.outputSchema(),
        source.tools(),
        source.prompts(),
        source.resources(),
        source.nodes(),
        source.ports(),
        edges,
        source.workflowOutput(),
        source.budgets(),
        source.approvals(),
        source.tests(),
        source.viewport()
    );
  }

  private static SkillDesignerDocument copyWithTests(
      final SkillDesignerDocument source,
      final List<TestCase> tests
  ) {
    return new SkillDesignerDocument(
        source.schemaVersion(),
        source.metadata(),
        source.inputSchema(),
        source.outputSchema(),
        source.tools(),
        source.prompts(),
        source.resources(),
        source.nodes(),
        source.ports(),
        source.edges(),
        source.workflowOutput(),
        source.budgets(),
        source.approvals(),
        tests,
        source.viewport()
    );
  }

  private SkillDesignerCompiler compiler() {
    SkillWorkflowTemplateResolver resolver =
        new SkillWorkflowTemplateResolver();
    return new SkillDesignerCompiler(
        adapter,
        new SkillWorkflowPlanCompiler(
            resolver,
            new SkillWorkflowConditionEvaluator(resolver)
        ),
        new SkillDesignerValidator(new SkillJsonSchemaValidator()),
        objectMapper
    );
  }

  private static Map<String, Object> completeManifest() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", "designer-round-trip");
    metadata.put("version", "1.2.3");
    metadata.put("title", "Designer round trip");

    Map<String, Object> inputSchema = Map.of(
        "type", "object",
        "required", List.of("text"),
        "additionalProperties", false,
        "properties", Map.of("text", Map.of("type", "string"))
    );
    final Map<String, Object> outputSchema = Map.of(
        "type", "object",
        "required", List.of("result"),
        "additionalProperties", false,
        "properties", Map.of("result", Map.of("type", "string"))
    );

    List<Map<String, Object>> steps = List.of(
        Map.of(
            "id", "prepare",
            "type", "tool",
            "tool", "echo",
            "arguments", Map.of(
                "message", Map.of("$ref", "input.text")
            )
        ),
        conditionStep(),
        parallelStep()
    );
    Map<String, Object> workflow = new LinkedHashMap<>();
    workflow.put("steps", steps);
    workflow.put("output", Map.of(
        "result",
        Map.of("$ref", "steps.fanout.branchResults.left.left-tool.text")
    ));

    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("inputSchema", inputSchema);
    spec.put("outputSchema", outputSchema);
    spec.put("workflow", workflow);
    spec.put("tools", List.of(Map.of(
        "alias", "echo",
        "serverId", "server-1",
        "snapshotId", "snapshot-1",
        "name", "echo"
    )));
    spec.put("prompts", List.of(Map.of(
        "alias", "welcome",
        "serverId", "server-1",
        "snapshotId", "snapshot-1",
        "name", "welcome"
    )));
    spec.put("resources", List.of(Map.of(
        "alias", "document",
        "serverId", "server-1",
        "snapshotId", "snapshot-1",
        "uriTemplate", "file:///{path}"
    )));
    spec.put("budgets", Map.of(
        "maximumToolCalls", 8,
        "maximumDurationSeconds", 120,
        "maximumPayloadBytes", 524288
    ));
    spec.put("approvals", Map.of(
        "execution", Map.of(
            "required", true,
            "allowSelfApproval", false,
            "instructions", "Review effectful calls"
        )
    ));

    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("apiVersion", "simplepoint.io/v1alpha1");
    manifest.put("kind", "Skill");
    manifest.put("metadata", metadata);
    manifest.put("spec", spec);
    return manifest;
  }

  private static Map<String, Object> conditionStep() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", "route");
    result.put("type", "condition");
    result.put("condition", Map.of(
        "equals",
        List.of(Map.of("$ref", "input.text"), "hello")
    ));
    result.put("then", List.of(Map.of(
        "id", "welcome-prompt",
        "type", "prompt",
        "prompt", "welcome",
        "arguments", Map.of("name", Map.of("$ref", "input.text"))
    )));
    result.put("else", List.of(Map.of(
        "id", "read-document",
        "type", "resource",
        "resource", "document",
        "uri", "file:///fallback.txt"
    )));
    return result;
  }

  private static Map<String, Object> parallelStep() {
    Map<String, Object> left = Map.of(
        "id", "left",
        "steps", List.of(Map.of(
            "id", "left-tool",
            "type", "tool",
            "tool", "echo",
            "arguments", Map.of("message", "left")
        ))
    );
    Map<String, Object> right = Map.of(
        "id", "right",
        "steps", List.of(Map.of(
            "id", "right-tool",
            "type", "tool",
            "tool", "echo",
            "arguments", Map.of("message", "right")
        ))
    );
    return Map.of(
        "id", "fanout",
        "type", "parallel",
        "branches", List.of(left, right)
    );
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(final Object value) {
    return (Map<String, Object>) value;
  }
}
