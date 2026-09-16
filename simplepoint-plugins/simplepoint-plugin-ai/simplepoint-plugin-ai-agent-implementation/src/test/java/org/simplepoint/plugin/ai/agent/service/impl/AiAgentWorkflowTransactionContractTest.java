package org.simplepoint.plugin.ai.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.agent.api.model.AgentWorkflowExecutionCommand;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AiAgentWorkflowTransactionContractTest {

  @Test
  void workflowStartMustJoinAtomicLaunchTransaction() throws Exception {
    Method method = AiAgentExecutionServiceImpl.class.getMethod(
        "startVersionForWorkflow",
        AgentWorkflowExecutionCommand.class
    );

    Transactional annotation = method.getAnnotation(Transactional.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(annotation.rollbackFor()).contains(Exception.class);
  }
}
