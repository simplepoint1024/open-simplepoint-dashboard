package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Shared public error mapping for record operations and relation directories. */
final class FormRecordFailures {
  private FormRecordFailures() {}

  static ApplicationFormException fromDna(final DnaRuntimeException exception) {
    ApplicationFormErrorCode code =
        switch (exception.errorCode()) {
          case RECORD_MAINTENANCE_IN_PROGRESS -> ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS;
          case RECORD_NOT_FOUND -> ApplicationFormErrorCode.RECORD_NOT_FOUND;
          case RECORD_OPERATION_REJECTED -> ApplicationFormErrorCode.RECORD_OPERATION_REJECTED;
          case RECORD_REFERENCE_CONFLICT -> ApplicationFormErrorCode.RECORD_REFERENCE_CONFLICT;
          case RECORD_ACCESS_DENIED -> ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED;
          case RECORD_VERSION_CONFLICT,
                  RECORD_IDEMPOTENCY_CONFLICT,
                  RECORD_WRITE_POLICY_CONFLICT,
                  RECORD_SCHEMA_INCOMPATIBLE,
                  PHYSICAL_MODEL_NOT_ACTIVE ->
              ApplicationFormErrorCode.RECORD_CONFLICT;
          case INVALID_ARGUMENT,
                  VALUE_CONVERSION_FAILED,
                  RECORD_QUERY_INVALID,
                  RECORD_COMMAND_INVALID ->
              ApplicationFormErrorCode.RECORD_INVALID;
          default -> ApplicationFormErrorCode.RECORD_OPERATION_FAILED;
        };
    return new ApplicationFormException(code, exception.getMessage(), exception);
  }
}
