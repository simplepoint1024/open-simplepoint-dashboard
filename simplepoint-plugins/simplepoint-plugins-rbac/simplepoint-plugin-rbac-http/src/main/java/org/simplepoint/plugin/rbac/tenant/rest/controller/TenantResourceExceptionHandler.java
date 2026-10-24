package org.simplepoint.plugin.rbac.tenant.rest.controller;

import java.util.Map;
import org.simplepoint.plugin.rbac.core.rest.controller.PlatformAccountController;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates tenant directory failures within the owning tenant HTTP adapters. */
@RestControllerAdvice(assignableTypes = {TenantController.class, TenantResourceController.class, PlatformAccountController.class})
public class TenantResourceExceptionHandler {
  @ExceptionHandler(org.simplepoint.plugin.rbac.tenant.api.error.LocalizedResourceNameException.class)
  public ResponseEntity<Map<String, String>> localizedNameFailure() {
    return ResponseEntity.badRequest().body(Map.of("code", "TENANT_RESOURCE:INVALID_LOCALIZED_NAME",
        "messageKey", "tenant.resources.localizedNameInvalid"));
  }
  /** Returns bounded diagnostics and a translation key without submitted values or persistence causes. */
  @ExceptionHandler(TenantResourceSetupException.class)
  public ResponseEntity<Map<String, String>> directoryFailure(TenantResourceSetupException error) {
    int status = switch (error.code()) {
      case OWNER_REQUIRED -> 403;
      case TENANT_UNAVAILABLE -> 404;
      case ADOPTION_REQUIRED, RESOURCE_CONFLICT -> 409;
      case CATALOG_NOT_READY, RESOURCE_UNAVAILABLE -> 503;
    };
    return ResponseEntity.status(status).body(Map.of(
        "code", "TENANT_RESOURCE_SETUP:" + error.code(), "messageKey", error.messageKey()));
  }
}
