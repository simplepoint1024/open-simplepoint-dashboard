package org.simplepoint.plugin.ai.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.agent.api.model.AgentPinnedChildCancelCommand;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AiAgentPinnedCancellationTransactionContractTest {

  @Test
  void pinnedCancellationRequiresAnOwningParentTransaction()
      throws Exception {
    Method method = AiAgentExecutionServiceImpl.class.getMethod(
        "cancelPinnedChild",
        AgentPinnedChildCancelCommand.class
    );

    Transactional annotation = method.getAnnotation(Transactional.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(annotation.rollbackFor()).contains(Exception.class);
  }
}
