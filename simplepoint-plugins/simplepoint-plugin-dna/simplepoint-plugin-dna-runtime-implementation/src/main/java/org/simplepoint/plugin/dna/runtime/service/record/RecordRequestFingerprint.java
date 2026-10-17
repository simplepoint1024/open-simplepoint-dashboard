package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.springframework.stereotype.Component;

/** Produces versioned semantic fingerprints for durable record operations. */
@Component
class RecordRequestFingerprint {

  private final ObjectMapper objectMapper;

  RecordRequestFingerprint(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  String of(final org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand command) {
    try {
      ObjectNode root = objectMapper.valueToTree(command);
      ((ObjectNode) root.get("parent")).remove(List.of("timeoutMs", "writePolicyVersion"));
      root.put("operation", "record.owned.v1");
      return sha256(objectMapper.writeValueAsBytes(canonical(root)));
    } catch (IllegalArgumentException | JsonProcessingException exception) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, "Owned record cannot be fingerprinted", exception);
    }
  }

  String of(final RecordCreateCommand command) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("operation", "record.create.v1");
      root.put("physicalModelId", command.physicalModelId());
      root.put("schemaVersion", command.schemaReference().version());
      root.put("schemaChecksum", command.schemaReference().checksum());
      root.set("values", canonical(objectMapper.valueToTree(command.values())));
      return sha256(objectMapper.writeValueAsBytes(root));
    } catch (IllegalArgumentException | JsonProcessingException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
          "Record create request cannot be fingerprinted",
          exception
      );
    }
  }

  String of(final RecordTransactionCommand command) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("operation", "record.transaction.v1");
      root.put("physicalModelId", command.physicalModelId());
      root.put("schemaVersion", command.schemaReference().version());
      root.put("schemaChecksum", command.schemaReference().checksum());
      root.set("mutations", canonical(objectMapper.valueToTree(command.mutations())));
      return sha256(objectMapper.writeValueAsBytes(root));
    } catch (IllegalArgumentException | JsonProcessingException exception) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
          "Record transaction cannot be fingerprinted", exception);
    }
  }

  String of(final RecordWritePolicyPublicationCommand command) {
    try {
      ObjectNode root = objectMapper.valueToTree(command);
      root.remove("timeoutMs"); // A retry may use a new execution budget without changing the durable intent.
      root.put("operation", "record.write-policy.v1");
      return sha256(objectMapper.writeValueAsBytes(canonical(root)));
    } catch (IllegalArgumentException | JsonProcessingException exception) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
          "Record policy publication cannot be fingerprinted", exception);
    }
  }

  /** Domain-separated tombstone identity; retains every original check and ignores only the retry budget. */
  String cancellationOf(final RecordWritePolicyPublicationCommand command) {
    return sha256(("record.write-policy.cancel.v1\0" + of(command)).getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode canonical(final JsonNode value) {
    if (value.isObject()) {
      ObjectNode result = objectMapper.createObjectNode();
      List<String> fields = new ArrayList<>();
      value.fieldNames().forEachRemaining(fields::add);
      fields.stream().sorted().forEach(field -> result.set(field, canonical(value.get(field))));
      return result;
    }
    if (value.isArray()) {
      ArrayNode result = objectMapper.createArrayNode();
      value.forEach(item -> result.add(canonical(item)));
      return result;
    }
    if (value.isNumber()) {
      return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
    }
    return value.deepCopy();
  }

  private static String sha256(final byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  static String deterministicRecordId(
      final String tenantId,
      final String physicalModelId,
      final String idempotencyKey
  ) {
    return deterministicId("record-create", tenantId, physicalModelId, idempotencyKey);
  }

  static String deterministicOperationId(String tenantId, String physicalModelId, String idempotencyKey) {
    return deterministicId("record-transaction", tenantId, physicalModelId, idempotencyKey);
  }

  private static String deterministicId(String namespace, String tenantId, String physicalModelId,
      String idempotencyKey) {
    String name = "simplepoint:dna:" + namespace + ":v1\0" + tenantId + "\0"
        + physicalModelId + "\0" + idempotencyKey;
    byte[] hash;
    try {
      hash = MessageDigest.getInstance("SHA-256")
          .digest(name.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
    hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
    hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
    long most = 0;
    long least = 0;
    for (int index = 0; index < 8; index++) {
      most = (most << 8) | (hash[index] & 0xffL);
      least = (least << 8) | (hash[index + 8] & 0xffL);
    }
    return new java.util.UUID(most, least).toString();
  }
}
