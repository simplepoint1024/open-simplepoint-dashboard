package org.simplepoint.plugin.ai.skill.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.model.SkillPinnedChildCancelCommand;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AiSkillPinnedCancellationTransactionContractTest {

  @Test
  void pinnedCancellationRequiresAnOwningParentTransaction()
      throws Exception {
    Method method = AiSkillExecutionServiceImpl.class.getMethod(
        "cancelPinnedChild",
        SkillPinnedChildCancelCommand.class
    );

    Transactional annotation = method.getAnnotation(Transactional.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(annotation.rollbackFor()).contains(Exception.class);
  }
}
