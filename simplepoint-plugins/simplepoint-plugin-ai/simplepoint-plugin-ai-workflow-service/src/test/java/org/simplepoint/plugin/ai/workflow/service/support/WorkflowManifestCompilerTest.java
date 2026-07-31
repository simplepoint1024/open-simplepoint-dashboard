package org.simplepoint.plugin.ai.workflow.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowDependencyType;

class WorkflowManifestCompilerTest {

  private final WorkflowManifestCompiler compiler =
      new WorkflowManifestCompiler();

  @Test
  void compilesBoundedDagAndPinsEveryCapabilityNode() {
    Map<String, Object> agent = node(
        "draft",
        "agent",
        "agentId",
        "agent-1",
        "versionId",
        "agent-version-1"
    );
    Map<String, Object> skill = node(
        "publish",
        "skill",
        "skillId",
        "skill-1",
        "versionId",
        "skill-version-1",
        "compensation",
        Map.of(
            "skillId",
            "rollback-skill",
            "versionId",
            "rollback-version"
        )
    );
    Map<String, Object> review = node(
        "review",
        "human",
        "title",
        "Review",
        "timeoutSeconds",
        300,
        "timeoutAction",
        "FAIL"
    );
    Map<String, Object> wait = node(
        "wait",
        "wait",
        "durationSeconds",
        1
    );
    Map<String, Object> end = node("done", "end");

    WorkflowManifestCompiler.CompiledWorkflow result =
        compiler.compile(manifest(
            List.of(agent, skill, review, wait, end),
            List.of(
                Map.of("from", "draft", "to", "publish"),
                Map.of("from", "publish", "to", "review"),
                Map.of("from", "review", "to", "wait"),
                Map.of("from", "wait", "to", "done")
            )
        ));

    assertThat(result.topologicalOrder())
        .containsExactly("draft", "publish", "review", "wait", "done");
    assertThat(result.dependencies())
        .extracting(WorkflowManifestCompiler.DependencyReference::type)
        .containsExactly(
            WorkflowDependencyType.AGENT,
            WorkflowDependencyType.SKILL,
            WorkflowDependencyType.COMPENSATION_SKILL
        );
    assertThat(result.budgets().maximumParallelism()).isEqualTo(4);
  }

  @Test
  void rejectsCyclesAndEmbeddedExecutableCode() {
    Map<String, Object> graph = manifest(
        List.of(node("one", "end"), node("two", "end")),
        List.of(
            Map.of("from", "one", "to", "two"),
            Map.of("from", "two", "to", "one")
        )
    );
    assertThatThrownBy(() -> compiler.compile(graph))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("acyclic");

    Map<String, Object> executable = manifest(
        List.of(node("one", "end")),
        List.of()
    );
    @SuppressWarnings("unchecked")
    Map<String, Object> spec =
        (Map<String, Object>) executable.get("spec");
    spec.put("script", "dangerous()");
    assertThatThrownBy(() -> compiler.compile(executable))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden executable field");
  }

  @Test
  void rejectsUnboundedHumanAndWaitNodes() {
    assertThatThrownBy(() -> compiler.compile(manifest(
        List.of(node(
            "review",
            "human",
            "title",
            "Review",
            "timeoutSeconds",
            604801
        )),
        List.of()
    ))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeoutSeconds");

    assertThatThrownBy(() -> compiler.compile(manifest(
        List.of(node(
            "pause",
            "wait",
            "durationSeconds",
            0
        )),
        List.of()
    ))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("durationSeconds");
  }

  private static Map<String, Object> manifest(
      final List<Map<String, Object>> nodes,
      final List<Map<String, Object>> edges
  ) {
    return mutableMap(
        "apiVersion",
        "simplepoint.io/v1alpha1",
        "kind",
        "AgentWorkflow",
        "metadata",
        Map.of("name", "test", "version", "1.0.0"),
        "spec",
        mutableMap(
            "inputSchema",
            Map.of("type", "object"),
            "outputSchema",
            Map.of("type", "object"),
            "budgets",
            Map.of(
                "maximumDurationSeconds",
                3600,
                "maximumNodeExecutions",
                32,
                "maximumParallelism",
                4
            ),
            "failurePolicy",
            Map.of(
                "mode",
                "FAIL_FAST",
                "compensation",
                "REVERSE_SUCCEEDED"
            ),
            "nodes",
            nodes,
            "edges",
            edges
        )
    );
  }

  private static Map<String, Object> node(
      final String id,
      final String type,
      final Object... entries
  ) {
    Map<String, Object> result = mutableMap(entries);
    result.put("id", id);
    result.put("type", type);
    return result;
  }

  private static Map<String, Object> mutableMap(
      final Object... entries
  ) {
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    for (int index = 0; index < entries.length; index += 2) {
      result.put(String.valueOf(entries[index]), entries[index + 1]);
    }
    return result;
  }
}
