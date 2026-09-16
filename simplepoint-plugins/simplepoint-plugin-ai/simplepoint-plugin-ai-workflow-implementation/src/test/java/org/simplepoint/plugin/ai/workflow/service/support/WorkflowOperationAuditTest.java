package org.simplepoint.plugin.ai.workflow.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkflowOperationAuditTest {

  @Test
  void sanitizesControlCharactersAndBoundsAuditValues() {
    assertThat(WorkflowOperationAudit.safe("line-1\nline-2\r"))
        .isEqualTo("line-1_line-2_");
    assertThat(WorkflowOperationAudit.safe("x".repeat(600))).hasSize(512);
    assertThat(WorkflowOperationAudit.safe("  ")).isEqualTo("-");
  }
}
