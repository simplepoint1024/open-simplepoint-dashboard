package org.simplepoint.plugin.ai.skill.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.model.SkillWorkflowExecutionCommand;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AiSkillWorkflowTransactionContractTest {

  @Test
  void workflowStartMustJoinAtomicLaunchTransaction() throws Exception {
    Method method = AiSkillExecutionServiceImpl.class.getMethod(
        "startVersionForWorkflow",
        SkillWorkflowExecutionCommand.class
    );

    Transactional annotation = method.getAnnotation(Transactional.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(annotation.rollbackFor()).contains(Exception.class);
  }
}
