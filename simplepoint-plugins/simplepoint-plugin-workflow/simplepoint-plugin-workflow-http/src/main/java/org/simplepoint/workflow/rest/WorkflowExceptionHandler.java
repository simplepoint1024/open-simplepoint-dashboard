package org.simplepoint.workflow.rest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable public error contract for workflow domain failures. */
@RestControllerAdvice(basePackageClasses = WorkflowDefinitionController.class)
public class WorkflowExceptionHandler {

  /** Converts workflow failures into the stable public error envelope. */
  @ExceptionHandler(WorkflowException.class)
  public ResponseEntity<Map<String, Object>> workflow(final WorkflowException exception) {
    final HttpStatus status =
        switch (exception.getCode()) {
          case VALIDATION -> HttpStatus.BAD_REQUEST;
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case CONFLICT -> HttpStatus.CONFLICT;
          case FORBIDDEN -> HttpStatus.FORBIDDEN;
          case ENGINE_FAILURE -> HttpStatus.UNPROCESSABLE_ENTITY;
          case DIRECTORY_PREPARING, DIRECTORY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", "WORKFLOW_" + exception.getCode().name());
    body.put("message", exception.getMessage());
    body.put("messageKey", "workflow.error." + exception.getCode().name());
    if (exception instanceof WorkflowModelingException modeling) {
      body.put("messageKey", modeling.getMessageKey());
      body.put("elementId", modeling.getElementId());
    }
    body.put("timestamp", Instant.now());
    var response = ResponseEntity.status(status);
    if (exception.getCode() == WorkflowException.Code.DIRECTORY_PREPARING
        || exception.getCode() == WorkflowException.Code.DIRECTORY_UNAVAILABLE) {
      int delay = exception.getCode() == WorkflowException.Code.DIRECTORY_PREPARING ? 2 : 5;
      body.put("retryable", true);
      body.put("retryAfterSeconds", delay);
      response.header("Retry-After", Integer.toString(delay));
    }
    return response.body(body);
  }
}
