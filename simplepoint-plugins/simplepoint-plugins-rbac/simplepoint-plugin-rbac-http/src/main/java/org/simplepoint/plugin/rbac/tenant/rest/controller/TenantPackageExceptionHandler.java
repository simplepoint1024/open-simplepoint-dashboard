package org.simplepoint.plugin.rbac.tenant.rest.controller;

import java.util.Map;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Package and historical entitlement diagnostics remain bounded at the owning HTTP boundary. */
@RestControllerAdvice(
    assignableTypes = {
      TenantLegacyAdoptionController.class,
      TenantPackageController.class,
      TenantPackageAdministrationController.class,
      TenantController.class,
      PackageController.class
    })
public class TenantPackageExceptionHandler {
  /**
   * Exposes stable diagnostic codes and translation keys, without database errors or submitted
   * values.
   */
  @ExceptionHandler(TenantPackageException.class)
  public ResponseEntity<Map<String, String>> packageFailure(TenantPackageException error) {
    int status =
        switch (error.code()) {
          case OWNER_REQUIRED, PLATFORM_ADMIN_REQUIRED -> 403;
          case LEGACY_ASSIGNMENT_RETIRED -> 410;
          case INSTANCE_NOT_FOUND, TENANT_NOT_FOUND -> 404;
          case INVALID_DURATION, INVALID_REQUEST -> 400;
          case RESOURCE_UNAVAILABLE, PACKAGE_UNAVAILABLE -> 503;
          case INSTANCE_REVOKED,
              INCONSISTENT_ACTIVATION,
              ALLOCATION_CONFLICT,
              RESOURCE_CONFLICT,
              INSTANCE_NOT_ACTIVATABLE,
              INSTANCE_NOT_SUPPLEMENTABLE,
              ADOPTION_NOT_REQUIRED ->
              409;
        };
    return ResponseEntity.status(status)
        .body(Map.of("code", "TENANT_PACKAGE:" + error.code(), "messageKey", error.messageKey()));
  }
}
