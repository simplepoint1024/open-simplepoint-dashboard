package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.springframework.stereotype.Component;

/** Bounded typed persistence; map ordering does not affect an operation's identity. */
@Component
class FormSubmissionCodec {
  private final ObjectMapper mapper;

  FormSubmissionCodec(ObjectMapper mapper) {
    this.mapper =
        mapper
            .copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  String write(Object value) {
    try {
      String json = mapper.writeValueAsString(value);
      if (json.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024) {
        throw new IllegalArgumentException("Submission exceeds 4 MiB");
      }
      return json;
    } catch (java.io.IOException | IllegalArgumentException failure) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_INVALID, "Submission cannot be encoded", failure);
    }
  }

  <T> T read(String json, Class<T> type) {
    try {
      if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024) {
        throw new IllegalArgumentException();
      }
      return mapper.readValue(json, type);
    } catch (java.io.IOException | IllegalArgumentException failure) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PERSISTENCE_FAILED, "Stored submission cannot be read", failure);
    }
  }

  String fingerprint(Object value) {
    return hash(write(value));
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }
}
