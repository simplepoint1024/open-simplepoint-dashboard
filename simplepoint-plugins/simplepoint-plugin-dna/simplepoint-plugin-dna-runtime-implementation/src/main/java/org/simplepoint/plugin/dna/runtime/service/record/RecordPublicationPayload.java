package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Bounded ASCII JSON permits deterministic chunking without splitting Unicode code points. */
final class RecordPublicationPayload {
  static final int CHUNK_BYTES = 64 * 1024;
  static final int MAX_BYTES = 64 * 1024 * 1024;
  private static final ObjectMapper JSON = JsonMapper.builder()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).enable(JsonWriteFeature.ESCAPE_NON_ASCII)
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

  static {
    JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_BYTES).build());
  }

  private RecordPublicationPayload() {
  }

  static String encode(final RecordPublicationAggregate aggregate) {
    var output = new BoundedWriter();
    try {
      JSON.writeValue(output, aggregate);
      return output.value.toString();
    } catch (IOException failure) {
      throw invalid(failure);
    }
  }

  static RecordPublicationAggregate decode(final String json) {
    if (json.isEmpty() || json.length() > MAX_BYTES || !ascii(json)) {
      throw invalid(null);
    }
    try {
      var aggregate = JSON.readValue(json, RecordPublicationAggregate.class);
      if (aggregate == null || !json.equals(encode(aggregate))) {
        throw invalid(null);
      }
      return aggregate;
    } catch (IOException | IllegalArgumentException failure) {
      throw invalid(failure);
    }
  }

  static boolean ascii(final String value) {
    return value.chars().allMatch(character -> character <= 127);
  }

  static String checksum(final String value) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (int offset = 0; offset < value.length(); offset += CHUNK_BYTES) {
        digest.update(value.substring(offset, Math.min(value.length(), offset + CHUNK_BYTES)).getBytes(StandardCharsets.US_ASCII));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  static DnaRuntimeException invalid(final Throwable cause) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Stored publication aggregate is invalid", cause);
  }

  private static final class BoundedWriter extends Writer {
    private final StringBuilder value = new StringBuilder();

    @Override
    public void write(final char[] buffer, final int offset, final int length) throws IOException {
      if (length > MAX_BYTES - value.length()) {
        throw new IOException("Publication aggregate payload budget exceeded");
      }
      value.append(buffer, offset, length);
    }

    @Override
    public void flush() {
      // The caller owns the physical transaction, not this bounded memory buffer.
    }

    @Override
    public void close() {
      // No external resources.
    }
  }
}
