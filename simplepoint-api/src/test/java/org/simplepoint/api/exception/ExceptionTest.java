package org.simplepoint.api.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExceptionTest {

  @Test
  void notImplementedException_defaultConstructor() {
    NotImplementedException ex = new NotImplementedException();
    assertThat(ex.getMessage()).isEqualTo("This feature is not implemented yet.");
    assertThat(ex).isInstanceOf(RuntimeException.class);
  }

  @Test
  void notImplementedException_withMessage() {
    NotImplementedException ex = new NotImplementedException("custom msg");
    assertThat(ex.getMessage()).isEqualTo("custom msg");
  }

  @Test
  void notImplementedException_withMessageAndCause() {
    Throwable cause = new IllegalStateException("root");
    NotImplementedException ex = new NotImplementedException("msg", cause);
    assertThat(ex.getMessage()).isEqualTo("msg");
    assertThat(ex.getCause()).isSameAs(cause);
  }

  @Test
  void notImplementedException_withCause() {
    Throwable cause = new IllegalStateException("root");
    NotImplementedException ex = new NotImplementedException(cause);
    assertThat(ex.getCause()).isSameAs(cause);
  }

  @Test
  void notInitializedException_withMessage() throws Exception {
    NotInitializedException ex = new NotInitializedException("not ready");
    assertThat(ex.getMessage()).isEqualTo("not ready");
    assertThat(ex).isInstanceOf(Exception.class);
  }
}
