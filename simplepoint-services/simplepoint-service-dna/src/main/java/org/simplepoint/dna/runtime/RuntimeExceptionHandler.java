package org.simplepoint.dna.runtime;

import java.time.Instant;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable HTTP mapping for Runtime API error codes and categories. */
@RestControllerAdvice(basePackageClasses = RuntimeRecordController.class)
public class RuntimeExceptionHandler {

  @ExceptionHandler(DnaRuntimeException.class)
  public ResponseEntity<RuntimeError> runtimeFailure(final DnaRuntimeException exception) {
    return ResponseEntity.status(status(exception.errorCode())).body(new RuntimeError(
        exception.errorCode().name(),
        exception.errorCode().category().name(),
        exception.getMessage(),
        "dna.runtime.error." + exception.errorCode().name(),
        Instant.now()
    ));
  }

  private static HttpStatus status(final DnaRuntimeErrorCode errorCode) {
    if (errorCode == DnaRuntimeErrorCode.RECORD_RATE_LIMITED) {
      return HttpStatus.TOO_MANY_REQUESTS;
    }
    return switch (errorCode.category()) {
      case VALIDATION -> HttpStatus.BAD_REQUEST;
      case AUTHORIZATION -> HttpStatus.FORBIDDEN;
      case NOT_FOUND -> HttpStatus.NOT_FOUND;
      case CONFLICT -> HttpStatus.CONFLICT;
      case CAPABILITY -> HttpStatus.UNPROCESSABLE_CONTENT;
      case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
  }

  /** Language-neutral transport error without database details or business values. */
  public record RuntimeError(String code, String category, String message, String messageKey, Instant timestamp) {
  }
}
