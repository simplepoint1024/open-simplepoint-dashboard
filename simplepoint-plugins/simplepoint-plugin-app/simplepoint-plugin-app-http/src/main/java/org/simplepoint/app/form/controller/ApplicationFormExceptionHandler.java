package org.simplepoint.app.form.controller;

import com.simplepoint.service.router.exception.ServiceRouteNotFoundException;
import java.time.Instant;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormCalculationDiagnostic;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/** Stable HTTP error mapping for the application form domain. */
@RestControllerAdvice(
    basePackageClasses = {
      ApplicationFormController.class,
      org.simplepoint.app.catalog.controller.WorkspaceApplicationController.class,
      org.simplepoint.app.catalog.controller.ApplicationNavigationController.class
    })
public class ApplicationFormExceptionHandler {

  /** Contract constructors reject malformed or unbounded localized values before domain execution. */
  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<FormError> invalidDocument(org.springframework.http.converter.HttpMessageNotReadableException exception) {
    return formFailure(new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, "Invalid application request document"));
  }

  /** Missing providers and interrupted transport preserve the original command for a safe retry. */
  @ExceptionHandler(ServiceRouteNotFoundException.class)
  public ResponseEntity<FormError> runtimeUnavailable(final RuntimeException exception) {
    // Transport diagnostics may contain endpoints or submitted values; never put them in this body.
    return formFailure(
        new ApplicationFormException(
            ApplicationFormErrorCode.RUNTIME_UNAVAILABLE,
            "The data service is temporarily unavailable. Retry the original operation."));
  }

  /** Response-header/body timeouts may be wrapped by RestClient outside ResourceAccessException. */
  @ExceptionHandler(RestClientException.class)
  public ResponseEntity<FormError> remoteFailure(final RestClientException exception) {
    if (exception instanceof ResourceAccessException || networkFailure(exception)) {
      return runtimeUnavailable(exception);
    }
    return formFailure(
        new ApplicationFormException(
            ApplicationFormErrorCode.RECORD_OPERATION_FAILED,
            "The data service response could not be read. Retry the original operation."));
  }

  private static boolean networkFailure(final Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
      if (current instanceof java.io.InterruptedIOException
          || current instanceof java.net.SocketException
          || current instanceof java.net.UnknownHostException) {
        return true;
      }
    }
    return false;
  }

  /** Converts domain failures without exposing persistence details. */
  @ExceptionHandler(ApplicationFormException.class)
  public ResponseEntity<FormError> formFailure(final ApplicationFormException exception) {
    return ResponseEntity.status(status(exception.errorCode()))
        .body(
            new FormError(
                exception.errorCode().name(),
                exception.getMessage(),
                "app.error." + exception.errorCode().name(),
                Instant.now(),
                exception.calculation()));
  }

  private static HttpStatus status(final ApplicationFormErrorCode code) {
    return switch (code) {
      case TENANT_CONTEXT_REQUIRED, ACTOR_CONTEXT_REQUIRED, RESOURCE_ACCESS_DENIED ->
          HttpStatus.FORBIDDEN;
      case APPLICATION_NOT_FOUND,
          FORM_NOT_FOUND,
          VERSION_NOT_FOUND,
          RECORD_NOT_FOUND,
          PAGE_NOT_FOUND,
          PUBLICATION_NOT_FOUND ->
          HttpStatus.NOT_FOUND;
      case FORM_CODE_CONFLICT,
          FORM_LIMIT_EXCEEDED,
          DRAFT_REVISION_CONFLICT,
          PUBLICATION_IDEMPOTENCY_CONFLICT,
          PUBLICATION_REVIEW_REQUIRED,
          PUBLICATION_DATA_CONFLICT,
          PUBLICATION_IN_PROGRESS,
          PAGE_BINDING_CONFLICT,
          PAGE_BINDING_SUPERSEDED,
          PAGE_NOT_PUBLISHED,
          RECORD_CONFLICT,
          RECORD_OPERATION_REJECTED,
          RUNTIME_VERSION_CONFLICT ->
          HttpStatus.CONFLICT;
      case FORM_ARCHIVED, DOCUMENT_INVALID, RECORD_INVALID -> HttpStatus.BAD_REQUEST;
      case PUBLICATION_FAILED, RECORD_OPERATION_FAILED -> HttpStatus.BAD_GATEWAY;
      case RUNTIME_UNAVAILABLE, NAVIGATION_SYNC_PENDING -> HttpStatus.SERVICE_UNAVAILABLE;
      case PERSISTENCE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
  }

  /** Language-neutral form error envelope. */
  public record FormError(
      String code,
      String message,
      String messageKey,
      Instant timestamp,
      FormCalculationDiagnostic calculation) {}
}
