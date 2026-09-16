package org.simplepoint.plugin.ai.agent.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentOperationAuditTest {

  @Test
  void sanitizesControlCharactersAndBoundsAuditValues() {
    assertThat(AgentOperationAudit.safe("line-1\nline-2\r"))
        .isEqualTo("line-1_line-2_");
    assertThat(AgentOperationAudit.safe("x".repeat(600))).hasSize(512);
    assertThat(AgentOperationAudit.safe("  ")).isEqualTo("-");
  }
}
