package org.simplepoint.app.form.controller;

import java.time.Instant;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable HTTP error mapping for the application form domain. */
@RestControllerAdvice(basePackageClasses = ApplicationFormController.class)
public class ApplicationFormExceptionHandler {

  /** Converts domain failures without exposing persistence details. */
  @ExceptionHandler(ApplicationFormException.class)
  public ResponseEntity<FormError> formFailure(final ApplicationFormException exception) {
    return ResponseEntity.status(status(exception.errorCode())).body(new FormError(
        exception.errorCode().name(),
        exception.getMessage(),
        "app.error." + exception.errorCode().name(),
        Instant.now()
    ));
  }

  private static HttpStatus status(final ApplicationFormErrorCode code) {
    return switch (code) {
      case TENANT_CONTEXT_REQUIRED, ACTOR_CONTEXT_REQUIRED,
          RESOURCE_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
      case APPLICATION_NOT_FOUND, FORM_NOT_FOUND, VERSION_NOT_FOUND, RECORD_NOT_FOUND,
          PAGE_NOT_FOUND, PUBLICATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case FORM_CODE_CONFLICT, FORM_LIMIT_EXCEEDED, DRAFT_REVISION_CONFLICT,
          PUBLICATION_IDEMPOTENCY_CONFLICT, PUBLICATION_REVIEW_REQUIRED, PUBLICATION_DATA_CONFLICT,
          PUBLICATION_IN_PROGRESS, PAGE_BINDING_CONFLICT, PAGE_BINDING_SUPERSEDED,
          PAGE_NOT_PUBLISHED, RECORD_CONFLICT, RUNTIME_VERSION_CONFLICT -> HttpStatus.CONFLICT;
      case FORM_ARCHIVED, DOCUMENT_INVALID, RECORD_INVALID -> HttpStatus.BAD_REQUEST;
      case PUBLICATION_FAILED, RECORD_OPERATION_FAILED -> HttpStatus.BAD_GATEWAY;
      case PERSISTENCE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
  }

  /** Language-neutral form error envelope. */
  public record FormError(String code, String message, String messageKey, Instant timestamp) {
  }
}
