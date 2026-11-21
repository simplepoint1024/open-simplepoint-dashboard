package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.databind.JsonNode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Historical rejection contracts shared by ordinary and collection operation receipts. */
final class RecordOperationRejection {
  private RecordOperationRejection() {}

  static String encode(DnaRuntimeErrorCode code) {
    return switch (code) {
      case RECORD_OPERATION_REJECTED -> "{\"version\":1,\"outcome\":\"REJECTED\"}";
      case RECORD_REFERENCE_CONFLICT ->
          "{\"version\":2,\"outcome\":\"REJECTED\",\"code\":\"RECORD_REFERENCE_CONFLICT\"}";
      default -> throw invalid();
    };
  }

  /** Unknown shapes remain for the operation codec to reject; they never imply success. */
  static void requireSuccess(JsonNode value) {
    if (value == null
        || !value.isObject()
        || !value.path("version").isIntegralNumber()
        || !value.path("version").canConvertToInt()
        || !value.path("outcome").isTextual()
        || !"REJECTED".equals(value.path("outcome").textValue())) {
      return;
    }
    if (value.size() == 2 && value.path("version").intValue() == 1) {
      throw rejected(DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED);
    }
    if (value.size() == 3
        && value.path("version").intValue() == 2
        && value.path("code").isTextual()
        && "RECORD_REFERENCE_CONFLICT".equals(value.path("code").textValue())) {
      throw rejected(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT);
    }
  }

  static DnaRuntimeException rejected(DnaRuntimeErrorCode code) {
    return switch (code) {
      case RECORD_REFERENCE_CONFLICT ->
          new DnaRuntimeException(
              code,
              "This operation was rejected by a reference constraint; submit a corrected command"
                  + " with a new key");
      case RECORD_OPERATION_REJECTED ->
          new DnaRuntimeException(
              code,
              "This operation was rejected by a data constraint; submit a corrected command with a"
                  + " new key");
      default -> throw invalid();
    };
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Record operation rejection receipt is invalid");
  }
}
