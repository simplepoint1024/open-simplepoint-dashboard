package org.simplepoint.plugin.rbac.tenant.rest.controller;

import java.util.Map;
import org.simplepoint.plugin.rbac.tenant.api.error.OrganizationLeaderException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OrganizationController.class)
public class OrganizationExceptionHandler {
  @ExceptionHandler(OrganizationLeaderException.class)
  public ResponseEntity<Map<String, String>> invalidLeader() {
    return ResponseEntity.badRequest().body(Map.of("code", "ORGANIZATION:INVALID_LEADER", "messageKey", "organizations.error.invalidLeader"));
  }
}
