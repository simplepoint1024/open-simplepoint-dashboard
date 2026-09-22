/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.handler;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.query.InvalidQueryException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Global Http response Exception handler. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(org.simplepoint.core.permission.InvalidPermissionPolicyException.class)
  public ResponseEntity<Map<String, String>> invalidPermissionPolicy(
      org.simplepoint.core.permission.InvalidPermissionPolicyException error) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_PERMISSION_POLICY", "message", error.getMessage()));
  }

  /** Reject invalid query input without exposing entity names, values or SQL details. */
  @ExceptionHandler(InvalidQueryException.class)
  public ResponseEntity<Map<String, String>> invalidQuery(InvalidQueryException error) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_QUERY", "message", error.getMessage()));
  }
}
