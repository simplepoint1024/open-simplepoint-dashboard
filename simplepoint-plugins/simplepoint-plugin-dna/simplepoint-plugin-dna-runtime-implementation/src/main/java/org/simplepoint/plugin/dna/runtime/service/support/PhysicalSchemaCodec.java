package org.simplepoint.plugin.dna.runtime.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.springframework.stereotype.Component;

/** Stable JSON serialization and checksum for immutable target schemas. */
@Component
public class PhysicalSchemaCodec {

  private final ObjectMapper objectMapper;

  /** Creates a canonical codec without modifying the application mapper. */
  public PhysicalSchemaCodec(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper.copy()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  /** Serializes and hashes one validated target schema. */
  public EncodedSchema encode(final PhysicalSchemaDefinition schema) {
    try {
      String json = objectMapper.writeValueAsString(schema);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String checksum = HexFormat.of().formatHex(
          digest.digest(json.getBytes(StandardCharsets.UTF_8))
      );
      return new EncodedSchema(json, checksum);
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_SERIALIZATION_FAILED,
          "Physical schema could not be encoded",
          exception
      );
    }
  }

  /** Restores a stored target schema and re-applies contract validation. */
  public PhysicalSchemaDefinition decode(final String schemaJson) {
    try {
      return objectMapper.readValue(schemaJson, PhysicalSchemaDefinition.class);
    } catch (JsonProcessingException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_SERIALIZATION_FAILED,
          "Stored physical schema could not be decoded",
          exception
      );
    }
  }

  /** Serialized schema and its SHA-256 checksum. */
  public record EncodedSchema(String json, String checksum) {
  }
}
