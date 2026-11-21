package org.simplepoint.plugin.dna.federation.rest.controller;

import java.time.Instant;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Typed write rejection with no SQL text, parser excerpts, values or datasource metadata. */
@RestControllerAdvice(assignableTypes = FederationSqlConsoleController.class)
public class FederationSqlWriteExceptionHandler {
  /** Projects a rejection without leaking parser or audit failure diagnostics. */
  @ExceptionHandler(FederationSqlWriteException.class)
  public ResponseEntity<WriteError> rejected(FederationSqlWriteException failure) {
    return ResponseEntity.badRequest()
        .body(new WriteError(failure.errorCode().name(), failure.messageKey(), Instant.now()));
  }

  /** Public localization envelope for SQL write admission. */
  public record WriteError(String code, String messageKey, Instant timestamp) {}
}
