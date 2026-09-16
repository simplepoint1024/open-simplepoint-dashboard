package org.simplepoint.plugin.ai.runtime.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Returns sanitized actionable errors from the Runtime management API. */
@RestControllerAdvice(basePackageClasses = AiRuntimePoolController.class)
public class AiRuntimeRestExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(
      AiRuntimeRestExceptionHandler.class
  );

  private static final String FALLBACK_ERROR_CODE =
      "AI_RUNTIME_REQUEST_REJECTED";

  /** Preserves an intentional HTTP status without exposing exception prose. */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> status(
      final ResponseStatusException exception,
      final HttpServletRequest request
  ) {
    String errorCode = stableErrorCode(exception.getReason());
    LOG.warn(
        "Runtime API rejected {} {} with {}",
        request.getMethod(),
        request.getRequestURI(),
        errorCode,
        exception
    );
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("status", exception.getStatusCode().value());
    body.put("error", HttpStatus.valueOf(
        exception.getStatusCode().value()
    ).getReasonPhrase());
    body.put("errorCode", errorCode);
    body.put("path", request.getRequestURI());
    return ResponseEntity.status(exception.getStatusCode()).body(body);
  }

  private static String stableErrorCode(final String reason) {
    if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{2,127}")) {
      return FALLBACK_ERROR_CODE;
    }
    return reason;
  }
}
