package org.simplepoint.plugin.ai.mcp.api.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/** Prevents MCP discovery diagnostics from crossing the public API boundary. */
public class AiMcpDiscoveryErrorCodeSerializer extends JsonSerializer<String> {

  @Override
  public void serialize(
      final String value,
      final JsonGenerator generator,
      final SerializerProvider serializers
  ) throws IOException {
    AiMcpErrorCode errorCode = AiMcpErrorCode.fromDiscoveryDiagnostic(value);
    if (errorCode == null) {
      generator.writeNull();
      return;
    }
    generator.writeString(errorCode.name());
  }
}
