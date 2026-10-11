package org.simplepoint.plugin.dna.runtime.service.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalColumnSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalIndexSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;
import org.springframework.stereotype.Component;

/** Canonical JSON and SHA-256 codec for normalized physical structures and statements. */
@Component
public class PhysicalSchemaSnapshotCodec {

  private final ObjectMapper objectMapper;

  /** Creates a codec with an isolated deterministic mapper. */
  public PhysicalSchemaSnapshotCodec(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper.copy();
  }

  /** Creates a canonical table snapshot and computes its checksum. */
  public PhysicalTableSnapshot snapshot(
      final boolean exists,
      final List<PhysicalColumnSnapshot> columns,
      final List<PhysicalIndexSnapshot> indexes,
      final String tableComment
  ) {
    List<PhysicalColumnSnapshot> sortedColumns = columns == null
        ? List.of()
        : columns.stream().sorted(java.util.Comparator.comparing(PhysicalColumnSnapshot::name)).toList();
    List<PhysicalIndexSnapshot> sortedIndexes = indexes == null
        ? List.of()
        : indexes.stream().sorted(java.util.Comparator.comparing(PhysicalIndexSnapshot::name)).toList();
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("exists", exists);
    canonical.put("columns", sortedColumns);
    canonical.put("indexes", sortedIndexes);
    canonical.put("tableComment", normalize(tableComment));
    return new PhysicalTableSnapshot(
        exists,
        sortedColumns,
        sortedIndexes,
        tableComment,
        checksumJson(canonical)
    );
  }

  /** Computes a checksum over a structured operation and generated native statement. */
  public String statementChecksum(final String operation, final String sql) {
    return sha256(operation + "\n" + sql);
  }

  /** Serializes a small structured recovery payload. */
  public String payload(final String logicalObject) {
    return json(Map.of("logicalObject", logicalObject));
  }

  private String checksumJson(final Object value) {
    return sha256(json(value));
  }

  private String json(final Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_SERIALIZATION_FAILED,
          "Physical schema canonicalization failed",
          exception
      );
    }
  }

  private static String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
      );
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String normalize(final String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
