package org.simplepoint.plugin.ai.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies the partial composite indexes used by dependency directories. */
class AiDependencyDirectorySchemaTest {

  @Test
  void schemaProvidesDependencyDirectoryIndexes() throws IOException {
    String schema = Files.readString(aiSchema());

    assertThat(schema)
        .contains("idx_simpoint_ai_model_dependency_options")
        .contains("idx_simpoint_ai_skill_dependency_options")
        .contains("idx_simpoint_ai_agent_dependency_options")
        .contains("AND model_type IN ('LLM', 'MULTIMODAL')")
        .contains("AND status IN ('PUBLISHED', 'DEPRECATED')");
  }

  private static Path aiSchema() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      Path candidate = current.resolve(
          "simplepoint-services/simplepoint-service-ai/"
              + "src/main/resources/schema.sql"
      );
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("AI schema.sql was not found");
  }
}
