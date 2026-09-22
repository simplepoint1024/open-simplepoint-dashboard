package org.simplepoint.core.jackson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.simplepoint.core.annotation.PermissionResource;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

/** Applies the shared request-scoped field policy to Spring Boot 4 HTTP serialization. */
public final class FieldScopeJackson3Module extends SimpleModule {
  /** Keeps the Jackson 3 adapter separate from the still-used Jackson 2 schema mapper. */
  public FieldScopeJackson3Module() {
    super("SimplePointFieldScopeJackson3");
    setSerializerModifier(new PolicyModifier());
  }

  private static final class PolicyModifier extends ValueSerializerModifier {
    @Override
    public List<BeanPropertyWriter> changeProperties(
        SerializationConfig config,
        BeanDescription.Supplier description,
        List<BeanPropertyWriter> properties) {
      Class<?> type = description.getBeanClass();
      if (!type.getPackageName().startsWith("org.simplepoint.")
          && !type.isAnnotationPresent(PermissionResource.class)) {
        return properties;
      }
      Map<String, String> names =
          description.get().findProperties().stream()
              .collect(
                  Collectors.toMap(
                      BeanPropertyDefinition::getName,
                      BeanPropertyDefinition::getInternalName,
                      (left, right) -> left));
      return properties.stream()
          .map(
              property ->
                  (BeanPropertyWriter)
                      new PolicyWriter(
                          property,
                          type,
                          names.getOrDefault(property.getName(), property.getName())))
          .collect(Collectors.toCollection(ArrayList::new));
    }
  }

  private static final class PolicyWriter extends BeanPropertyWriter {
    private final Class<?> resource;
    private final String field;

    private PolicyWriter(BeanPropertyWriter original, Class<?> resource, String field) {
      super(original);
      this.resource = resource;
      this.field = field;
    }

    @Override
    public void serializeAsProperty(
        Object bean, JsonGenerator generator, SerializationContext context) throws Exception {
      String access = FieldPermissionPolicy.access(resource, field);
      if (FieldPermissionPolicy.hidden(access)) {
        return;
      }
      if ("MASKED".equals(access)) {
        generator.writeName(getName());
        redact(bean, generator);
      } else {
        super.serializeAsProperty(bean, generator, context);
      }
    }

    @Override
    public void serializeAsElement(
        Object bean, JsonGenerator generator, SerializationContext context) throws Exception {
      String access = FieldPermissionPolicy.access(resource, field);
      if (FieldPermissionPolicy.hidden(access)) {
        generator.writeNull();
      } else if ("MASKED".equals(access)) {
        redact(bean, generator);
      } else {
        super.serializeAsElement(bean, generator, context);
      }
    }

    private void redact(Object bean, JsonGenerator generator) throws Exception {
      if (get(bean) == null) {
        generator.writeNull();
      } else {
        generator.writeString("***");
      }
    }
  }
}
