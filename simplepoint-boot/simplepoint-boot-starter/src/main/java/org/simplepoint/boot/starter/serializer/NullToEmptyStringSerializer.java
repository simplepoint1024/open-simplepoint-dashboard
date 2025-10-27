package org.simplepoint.boot.starter.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/**
 * A custom serializer that converts null values to empty strings during JSON serialization.
 *
 * <p>This serializer can be used in conjunction with Jackson's serialization framework
 * to ensure that any null values in the serialized objects are represented as empty strings
 * in the resulting JSON output.</p>
 */
public class NullToEmptyStringSerializer extends JsonSerializer<Object> {
  @Override
  public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
    gen.writeString("");
  }
}
