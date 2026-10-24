package org.simplepoint.plugin.rbac.tenant.rest.controller;

import java.util.Map;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationException;
import org.simplepoint.plugin.rbac.tenant.api.error.DictionaryWriteException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Preserves dictionary domain diagnostics without exposing internal persistence failures. */
@RestControllerAdvice(
    assignableTypes = {DictionaryController.class, DictionaryItemController.class})
public class DictionaryExceptionHandler {
  /** Returns stable codes/translation keys; no SQL, submitted values or causes cross HTTP. */
  @ExceptionHandler(DictionaryWriteException.class)
  public ResponseEntity<Map<String, String>> dictionaryWrite(DictionaryWriteException error) {
    int status = error.code() == DictionaryWriteException.Code.CONTEXT_CONFLICT ? 409 : 503;
    return ResponseEntity.status(status)
        .body(Map.of("code", "DICTIONARY_WRITE:" + error.code(), "messageKey", error.messageKey()));
  }

  /**
   * Managed dictionary identities cannot be renamed, recreated or removed through ordinary CRUD.
   */
  @ExceptionHandler(DictionaryRegistrationException.class)
  public ResponseEntity<Map<String, String>> dictionaryRegistration(
      DictionaryRegistrationException error) {
    int status =
        switch (error.code()) {
          case ACCESS_DENIED -> 403;
          case INVALID_DECLARATION -> 400;
          case CATALOG_UNAVAILABLE -> 503;
          default -> 409;
        };
    return ResponseEntity.status(status)
        .body(Map.of("code", error.remoteErrorCode(), "messageKey", error.messageKey()));
  }
}
