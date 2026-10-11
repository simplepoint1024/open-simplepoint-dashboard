package org.simplepoint.dna.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.springframework.http.HttpStatus;

class RuntimeExceptionHandlerTest {

  private final RuntimeExceptionHandler handler = new RuntimeExceptionHandler();

  @Test
  void shouldExposeStableStatusAndLanguageNeutralErrorFields() {
    Map<DnaRuntimeErrorCode, HttpStatus> expected = Map.of(
        DnaRuntimeErrorCode.RECORD_QUERY_INVALID, HttpStatus.BAD_REQUEST,
        DnaRuntimeErrorCode.RECORD_ACCESS_DENIED, HttpStatus.FORBIDDEN,
        DnaRuntimeErrorCode.RECORD_NOT_FOUND, HttpStatus.NOT_FOUND,
        DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT, HttpStatus.CONFLICT,
        DnaRuntimeErrorCode.RECORD_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
        DnaRuntimeErrorCode.DIALECT_OPERATION_UNSUPPORTED, HttpStatus.UNPROCESSABLE_CONTENT,
        DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, HttpStatus.INTERNAL_SERVER_ERROR
    );

    expected.forEach((code, status) -> {
      var response = handler.runtimeFailure(new DnaRuntimeException(code, "safe message"));
      assertThat(response.getStatusCode()).isEqualTo(status);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().code()).isEqualTo(code.name());
      assertThat(response.getBody().category()).isEqualTo(code.category().name());
      assertThat(response.getBody().message()).isEqualTo("safe message");
      assertThat(response.getBody().timestamp()).isNotNull();
    });
  }
}
