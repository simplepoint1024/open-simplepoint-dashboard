package org.simplepoint.plugin.ai.skill.service.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerCompilationResult;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDiagnostic;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.AssertionOperator;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.MockMode;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Node;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.NodeMock;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Port;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestAssertion;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestCase;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowConditionEvaluator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;

class SkillDesignerValidatorTest {

  private SkillDesignerManifestAdapter adapter;

  private SkillDesignerValidator validator;

  private SkillDesignerCompiler compiler;

  @BeforeEach
  void setUp() {
    adapter = new SkillDesignerManifestAdapter();
    validator = new SkillDesignerValidator(new SkillJsonSchemaValidator());
    SkillWorkflowTemplateResolver resolver =
        new SkillWorkflowTemplateResolver();
    compiler = new SkillDesignerCompiler(
        adapter,
        new SkillWorkflowPlanCompiler(
            resolver,
            new SkillWorkflowConditionEvaluator(resolver)
        ),
        validator,
        new ObjectMapper()
    );
  }

  @Test
  void acceptsNormalizedDocumentWithoutDiagnostics() {
    SkillDesignerDocument document = validDocument();

    assertThat(validator.validate(document)).isEmpty();
    assertThat(compiler.compile(document).valid()).isTrue();
  }

  @Test
  void reportsMissingDocumentFieldsWithoutThrowing() {
    SkillDesignerDocument incomplete = new SkillDesignerDocument(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    );

    SkillDesignerCompilationResult result = compiler.compile(incomplete);

    assertThat(result.valid()).isFalse();
    assertThat(result.diagnostics())
        .extracting(SkillDesignerDiagnostic::code)
        .contains(
            "SKILL_DESIGNER_VERSION_UNSUPPORTED",
            "SKILL_DESIGNER_FIELD_REQUIRED",
            "SKILL_DESIGNER_NODE_COUNT_INVALID",
            "SKILL_DESIGNER_VIRTUAL_NODE_INVALID",
            "SKILL_DESIGNER_WORKFLOW_EMPTY"
        );
    assertThat(compiler.compile(null).diagnostics())
        .singleElement()
        .satisfies(diagnostic -> assertThat(diagnostic.code())
            .isEqualTo("SKILL_DESIGNER_DOCUMENT_REQUIRED"));
  }

  @Test
  void aggregatesIndependentErrorsWithNodeAndFieldLocations() {
    SkillDesignerDocument source = validDocument();
    List<Node> nodes = new ArrayList<>(source.nodes());
    Node first = nodes.stream()
        .filter(node -> "first".equals(node.id()))
        .findFirst()
        .orElseThrow();
    nodes.set(nodes.indexOf(first), new Node(
        first.id(),
        first.type(),
        first.parentNodeId(),
        first.branchId(),
        first.order(),
        Map.of(
            "tool", "missing",
            "arguments", Map.of(
                "value", Map.of("$ref", "steps.second.value")
            )
        ),
        first.position()
    ));
    List<Port> ports = source.ports().stream()
        .filter(port -> !("first".equals(port.nodeId())
            && "out".equals(port.id())))
        .toList();
    TestCase test = new TestCase(
        "invalid-case",
        "Invalid case",
        true,
        Map.of(),
        List.of(new NodeMock(
            SkillDesignerDocument.INPUT_NODE_ID,
            MockMode.ERROR,
            null,
            null,
            "failure"
        )),
        List.of(new TestAssertion(
            "unknown-source",
            "unknown-node",
            "value",
            AssertionOperator.EQUALS,
            "expected"
        ))
    );
    SkillDesignerDocument invalid = copy(
        source,
        Map.of("type", "unsupported"),
        List.of(source.tools().getFirst(), source.tools().getFirst()),
        nodes,
        ports,
        List.of(test)
    );

    SkillDesignerCompilationResult result = compiler.compile(invalid);

    assertThat(result.valid()).isFalse();
    assertThat(result.manifest()).isNull();
    assertThat(result.diagnostics())
        .extracting(SkillDesignerDiagnostic::code)
        .contains(
            "SKILL_DESIGNER_INPUT_SCHEMA_INVALID",
            "SKILL_DESIGNER_BINDING_ALIAS_DUPLICATE",
            "SKILL_DESIGNER_CAPABILITY_UNBOUND",
            "SKILL_DESIGNER_REFERENCE_NOT_AVAILABLE",
            "SKILL_DESIGNER_PORT_MISSING",
            "SKILL_DESIGNER_EDGE_PORT_UNKNOWN",
            "SKILL_DESIGNER_TEST_MOCK_TARGET_INVALID",
            "SKILL_DESIGNER_TEST_MOCK_ERROR_CODE_REQUIRED",
            "SKILL_DESIGNER_TEST_ASSERTION_SOURCE_INVALID"
        );
    assertThat(result.diagnostics())
        .filteredOn(diagnostic ->
            "SKILL_DESIGNER_REFERENCE_NOT_AVAILABLE".equals(
                diagnostic.code()
            ))
        .singleElement()
        .satisfies(diagnostic -> {
          assertThat(diagnostic.nodeId()).isEqualTo("first");
          assertThat(diagnostic.fieldPath())
              .isEqualTo("configuration.arguments.value");
          assertThat(diagnostic.jsonPointer())
              .endsWith("/configuration/arguments/value/$ref");
        });
  }

  @Test
  void compilesMaximumSupportedLinearCanvasWithinBudget() {
    List<Map<String, Object>> steps = new ArrayList<>();
    for (int index = 0; index < 128; index++) {
      steps.add(Map.of(
          "id", "step-" + index,
          "type", "tool",
          "tool", "echo",
          "arguments", Map.of()
      ));
    }
    SkillDesignerDocument document = documentWithSteps(steps);

    SkillDesignerCompilationResult result = assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> compiler.compile(document)
    );

    assertThat(result.valid()).isTrue();
    assertThat(result.contentHash()).hasSize(64);
  }

  @Test
  void rejectsCanvasAboveNodeLimitWithStructuredDiagnostic() {
    List<Map<String, Object>> steps = new ArrayList<>();
    for (int index = 0; index < 129; index++) {
      steps.add(Map.of(
          "id", "step-" + index,
          "type", "tool",
          "tool", "echo",
          "arguments", Map.of()
      ));
    }

    SkillDesignerCompilationResult result = compiler.compile(
        documentWithSteps(steps)
    );

    assertThat(result.valid()).isFalse();
    assertThat(result.diagnostics())
        .extracting(SkillDesignerDiagnostic::code)
        .contains(
            "SKILL_DESIGNER_NODE_COUNT_INVALID",
            "SKILL_DESIGNER_NODE_LIMIT_EXCEEDED"
        );
  }

  private SkillDesignerDocument documentWithSteps(
      final List<Map<String, Object>> steps
  ) {
    return adapter.fromManifest(Map.of(
        "apiVersion", "simplepoint.io/v1alpha1",
        "kind", "Skill",
        "metadata", Map.of(
            "name", "large-canvas",
            "version", "0.1.0"
        ),
        "spec", Map.of(
            "inputSchema", Map.of("type", "object"),
            "outputSchema", Map.of("type", "object"),
            "tools", List.of(Map.of(
                "alias", "echo",
                "serverId", "server-a",
                "snapshotId", "snapshot-a",
                "name", "echo"
            )),
            "workflow", Map.of("steps", steps)
        )
    ));
  }

  private SkillDesignerDocument validDocument() {
    return adapter.fromManifest(Map.of(
        "apiVersion", "simplepoint.io/v1alpha1",
        "kind", "Skill",
        "metadata", Map.of(
            "name", "validator-test",
            "version", "0.1.0"
        ),
        "spec", Map.of(
            "inputSchema", Map.of("type", "object"),
            "outputSchema", Map.of("type", "object"),
            "tools", List.of(Map.of(
                "alias", "echo",
                "serverId", "server-a",
                "snapshotId", "snapshot-a",
                "name", "echo"
            )),
            "workflow", Map.of(
                "steps", List.of(
                    Map.of(
                        "id", "first",
                        "type", "tool",
                        "tool", "echo",
                        "arguments", Map.of()
                    ),
                    Map.of(
                        "id", "second",
                        "type", "tool",
                        "tool", "echo",
                        "arguments", Map.of(
                            "value",
                            Map.of("$ref", "steps.first.value")
                        )
                    )
                ),
                "output", Map.of(
                    "value",
                    Map.of("$ref", "steps.second.value")
                )
            )
        )
    ));
  }

  private SkillDesignerDocument copy(
      final SkillDesignerDocument source,
      final Map<String, Object> inputSchema,
      final List<Map<String, Object>> tools,
      final List<Node> nodes,
      final List<Port> ports,
      final List<TestCase> tests
  ) {
    return new SkillDesignerDocument(
        source.schemaVersion(),
        source.metadata(),
        inputSchema,
        source.outputSchema(),
        tools,
        source.prompts(),
        source.resources(),
        nodes,
        ports,
        source.edges(),
        source.workflowOutput(),
        source.budgets(),
        source.approvals(),
        tests,
        source.viewport()
    );
  }
}
