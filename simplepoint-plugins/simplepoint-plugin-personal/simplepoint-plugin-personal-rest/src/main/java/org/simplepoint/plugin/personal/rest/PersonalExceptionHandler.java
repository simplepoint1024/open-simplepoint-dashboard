package org.simplepoint.plugin.personal.rest;

import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable, non-sensitive error payloads for personal work APIs. */
@RestControllerAdvice(assignableTypes = PersonalController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PersonalExceptionHandler {
  /** Reports safe domain validation messages. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
  }

  /** Uses the same response for missing and foreign-owned records. */
  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<Map<String, String>> missing(NoSuchElementException error) {
    return ResponseEntity.status(404).body(Map.of("message", "记录不存在或无权访问"));
  }

  /** Reports conflicts without exposing SQL or persistence details. */
  @ExceptionHandler({
    OptimisticLockingFailureException.class,
    jakarta.persistence.OptimisticLockException.class,
    DataIntegrityViolationException.class
  })
  public ResponseEntity<Map<String, String>> conflict(Exception error) {
    return ResponseEntity.status(409).body(Map.of("message", "记录已变更或重复，请刷新后重试；当前编辑内容未保存"));
  }
}
