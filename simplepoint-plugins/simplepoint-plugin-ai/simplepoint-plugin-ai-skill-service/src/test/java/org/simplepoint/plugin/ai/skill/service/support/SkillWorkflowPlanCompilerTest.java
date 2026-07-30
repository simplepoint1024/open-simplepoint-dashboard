package org.simplepoint.plugin.ai.skill.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ConditionNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ParallelNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.PromptNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ResourceNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.WorkflowBindings;

class SkillWorkflowPlanCompilerTest {

  private SkillWorkflowPlanCompiler compiler;

  @BeforeEach
  void setUp() {
    SkillWorkflowTemplateResolver resolver =
        new SkillWorkflowTemplateResolver();
    compiler = new SkillWorkflowPlanCompiler(
        resolver,
        new SkillWorkflowConditionEvaluator(resolver)
    );
  }

  @Test
  void compilesConditionAndParallelNodesWithWorstCaseBudget() {
    Map<String, Object> workflow = Map.of(
        "steps",
        List.of(
            tool("prepare", null),
            Map.of(
                "id", "route",
                "type", "condition",
                "condition", Map.of(
                    "equals",
                    List.of(Map.of("$ref", "input.mode"), "full")
                ),
                "then", List.of(
                    tool("full-a", "steps.prepare.structuredContent.value"),
                    tool("full-b", "steps.full-a.structuredContent.value")
                ),
                "else", List.of(tool(
                    "compact",
                    "steps.prepare.structuredContent.value"
                ))
            ),
            Map.of(
                "id", "fanout",
                "type", "parallel",
                "branches", List.of(
                    Map.of(
                        "id", "left",
                        "steps", List.of(
                            tool("left-a", null),
                            tool(
                                "left-b",
                                "steps.left-a.structuredContent.value"
                            )
                        )
                    ),
                    Map.of(
                        "id", "right",
                        "steps", List.of(tool("right-a", null))
                    )
                )
            )
        ),
        "output", Map.of(
            "selected",
            Map.of("$ref", "steps.route.selectedBranch")
        )
    );

    SkillWorkflowPlanCompiler.WorkflowPlan plan =
        compiler.compile(workflow, Set.of("echo"));

    assertThat(plan.maximumToolCalls()).isEqualTo(6);
    assertThat(plan.executableSteps()).hasSize(7);
    assertThat(plan.nodes().get(1)).isInstanceOf(ConditionNode.class);
    assertThat(plan.nodes().get(2)).isInstanceOf(ParallelNode.class);
  }

  @Test
  void compilesPinnedPromptAndResourceWithoutConsumingToolBudget() {
    Map<String, Object> workflow = Map.of(
        "steps", List.of(
            Map.of(
                "id", "prompt-a",
                "type", "prompt",
                "prompt", "welcome",
                "arguments", Map.of("name", Map.of("$ref", "input.name"))
            ),
            Map.of(
                "id", "resource-a",
                "type", "resource",
                "resource", "document",
                "uri", Map.of("$ref", "input.uri")
            )
        )
    );

    SkillWorkflowPlanCompiler.WorkflowPlan plan = compiler.compile(
        workflow,
        new WorkflowBindings(Set.of(), Set.of("welcome"), Set.of("document"))
    );

    assertThat(plan.maximumToolCalls()).isZero();
    assertThat(plan.executableSteps()).hasSize(2);
    assertThat(plan.nodes().get(0)).isInstanceOf(PromptNode.class);
    assertThat(plan.nodes().get(1)).isInstanceOf(ResourceNode.class);
  }

  @Test
  void rejectsReferencesAcrossIndependentParallelBranches() {
    Map<String, Object> workflow = Map.of(
        "steps",
        List.of(Map.of(
            "id", "fanout",
            "type", "parallel",
            "branches", List.of(
                Map.of(
                    "id", "left",
                    "steps", List.of(tool("left-a", null))
                ),
                Map.of(
                    "id", "right",
                    "steps", List.of(tool(
                        "right-a",
                        "steps.left-a.structuredContent.value"
                    ))
                )
            )
        ))
    );

    assertThatThrownBy(() -> compiler.compile(workflow, Set.of("echo")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unavailable workflow data");
  }

  @Test
  void rejectsNestedControlNodesInV1Alpha1() {
    Map<String, Object> workflow = Map.of(
        "steps",
        List.of(Map.of(
            "id", "outer",
            "type", "condition",
            "condition", Map.of("isTrue", true),
            "then", List.of(Map.of(
                "id", "nested",
                "type", "parallel",
                "branches", List.of()
            ))
        ))
    );

    assertThatThrownBy(() -> compiler.compile(workflow, Set.of("echo")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Nested workflow control node");
  }

  private static Map<String, Object> tool(
      final String id,
      final String reference
  ) {
    if (reference == null) {
      return Map.of(
          "id", id,
          "type", "tool",
          "tool", "echo"
      );
    }
    return Map.of(
        "id", id,
        "type", "tool",
        "tool", "echo",
        "arguments", Map.of(
            "message",
            Map.of("$ref", reference)
        )
    );
  }
}
