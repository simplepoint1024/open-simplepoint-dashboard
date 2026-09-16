package org.simplepoint.plugin.ai.knowledge.api.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/** Prevents indexing diagnostics from crossing the public API boundary. */
public class AiKnowledgeErrorCodeSerializer extends JsonSerializer<String> {

  @Override
  public void serialize(
      final String value,
      final JsonGenerator generator,
      final SerializerProvider serializers
  ) throws IOException {
    AiKnowledgeErrorCode errorCode = AiKnowledgeErrorCode.fromDiagnostic(value);
    if (errorCode == null) {
      generator.writeNull();
      return;
    }
    generator.writeString(errorCode.name());
  }
}
