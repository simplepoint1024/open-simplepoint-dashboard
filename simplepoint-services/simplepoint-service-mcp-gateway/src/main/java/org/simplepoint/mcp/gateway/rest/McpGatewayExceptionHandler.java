package org.simplepoint.mcp.gateway.rest;

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sanitizes errors returned by the private Gateway API.
 */
@RestControllerAdvice
public class McpGatewayExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(McpGatewayExceptionHandler.class);

  /**
   * Handles validation failures.
   *
   * @param exception validation exception
   * @return bad request response
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> validation(final IllegalArgumentException exception) {
    return response(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  /**
   * Preserves intentional HTTP status responses from publication-aware endpoints.
   *
   * @param exception status exception
   * @return sanitized response with the requested status
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> status(final ResponseStatusException exception) {
    HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
    return response(status, exception.getReason());
  }

  /**
   * Handles upstream protocol and transport failures without returning stack traces.
   *
   * @param exception failure
   * @return bad gateway response
   */
  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<Map<String, Object>> upstream(final RuntimeException exception) {
    LOG.warn("Remote MCP operation failed: {}", exception.getMessage(), exception);
    return response(HttpStatus.BAD_GATEWAY, "Remote MCP operation failed");
  }

  private static ResponseEntity<Map<String, Object>> response(
      final HttpStatus status,
      final String message
  ) {
    return ResponseEntity.status(status).body(Map.of(
        "timestamp", Instant.now().toString(),
        "status", status.value(),
        "error", status.getReasonPhrase(),
        "message", message == null ? status.getReasonPhrase() : message
    ));
  }
}
