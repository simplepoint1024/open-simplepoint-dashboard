package org.simplepoint.api.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InitTaskTest {

  @Test
  void initTask_recordFields() {
    InitTask.Initializer initializer = () -> {};
    InitTask task = new InitTask("myModule", initializer);
    assertThat(task.moduleName()).isEqualTo("myModule");
    assertThat(task.task()).isSameAs(initializer);
  }

  @Test
  void initializer_runIsInvoked() throws Exception {
    boolean[] invoked = {false};
    InitTask.Initializer initializer = () -> invoked[0] = true;
    new InitTask("m", initializer).task().run();
    assertThat(invoked[0]).isTrue();
  }

  @Test
  void initializer_canThrow() {
    InitTask.Initializer initializer = () -> {
      throw new RuntimeException("fail");
    };
    InitTask task = new InitTask("failing", initializer);
    assertThatThrownBy(() -> task.task().run()).isInstanceOf(RuntimeException.class).hasMessage("fail");
  }

  @Test
  void initTask_equality() {
    InitTask.Initializer init = () -> {};
    InitTask t1 = new InitTask("mod", init);
    InitTask t2 = new InitTask("mod", init);
    assertThat(t1).isEqualTo(t2);
  }
}
