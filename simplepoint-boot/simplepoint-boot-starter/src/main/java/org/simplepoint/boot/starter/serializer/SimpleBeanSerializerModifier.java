package org.simplepoint.boot.starter.serializer;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import java.util.List;

/**
 * A custom BeanSerializerModifier that modifies the serialization behavior of bean properties.
 *
 * <p>This modifier checks each property of a bean during serialization,
 * and if the property is of type String, it assigns a custom serializer
 * that converts null values to empty strings.</p>
 */
public class SimpleBeanSerializerModifier extends BeanSerializerModifier {
  @Override
  public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                   BeanDescription beanDesc,
                                                   List<BeanPropertyWriter> beanProperties) {
    for (BeanPropertyWriter writer : beanProperties) {
      if (writer.getType().getRawClass().equals(String.class)) {
        writer.assignNullSerializer(new NullToEmptyStringSerializer());
      }
    }
    return beanProperties;
  }
}
