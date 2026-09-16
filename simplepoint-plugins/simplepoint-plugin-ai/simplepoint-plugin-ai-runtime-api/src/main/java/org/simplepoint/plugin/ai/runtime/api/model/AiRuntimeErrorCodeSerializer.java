package org.simplepoint.plugin.ai.runtime.api.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/**
 * Prevents internal Runtime diagnostics from crossing the public API boundary.
 */
public class AiRuntimeErrorCodeSerializer extends JsonSerializer<String> {

  @Override
  public void serialize(
      final String value,
      final JsonGenerator generator,
      final SerializerProvider serializers
  ) throws IOException {
    AiRuntimeErrorCode errorCode = AiRuntimeErrorCode.fromDiagnostic(value);
    if (errorCode == null) {
      generator.writeNull();
      return;
    }
    generator.writeString(errorCode.name());
  }
}
