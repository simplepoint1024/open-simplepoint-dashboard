package org.simplepoint.dna.model.controller;

import java.time.Instant;
import org.simplepoint.plugin.dna.federation.api.error.DnaModelException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Public error projection for the query model catalog. */
@RestControllerAdvice(basePackageClasses = DnaDataModelController.class)
public class DnaModelExceptionHandler {
  /** Keeps status codes stable while providing language-neutral client diagnostics. */
  @ExceptionHandler(DnaModelException.class)
  public ResponseEntity<ModelError> modelFailure(DnaModelException exception) {
    var status =
        switch (exception.errorCode()) {
          case MODEL_INVALID -> HttpStatus.BAD_REQUEST;
          case MODEL_NOT_FOUND -> HttpStatus.NOT_FOUND;
          case MODEL_VERSION_CONFLICT -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status)
        .body(
            new ModelError(
                exception.errorCode().name(),
                "dna.models.error." + exception.errorCode().name(),
                Instant.now()));
  }

  /** Does not disclose submitted SQL or persistence diagnostics. */
  public record ModelError(String code, String messageKey, Instant timestamp) {}
}
