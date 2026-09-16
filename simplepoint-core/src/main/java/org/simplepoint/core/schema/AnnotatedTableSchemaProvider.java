package org.simplepoint.core.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.jackson.FieldPermissionPolicy;

/**
 * Builds the schema contract consumed by the platform {@code SimpleTable} component.
 *
 * <p>The provider is deliberately independent of repositories so read models and audit
 * projections can use the same annotation-driven table contract as regular entities.</p>
 */
@Slf4j
public final class AnnotatedTableSchemaProvider {
  private final DetailsProviderService detailsProviderService;
  private final ObjectMapper mapper = new ObjectMapper();

  public AnnotatedTableSchemaProvider(DetailsProviderService detailsProviderService) {
    this.detailsProviderService = detailsProviderService;
  }

  public Map<String, Object> schema(Class<?> modelClass, AuthorizationContext context) {
    Map<String, Object> schema = mapper.convertValue(jsonSchema(modelClass), new TypeReference<>() { });
    return Map.of("schema", schema, "buttons", buttonSchemas(modelClass, context));
  }

  public ObjectNode jsonSchema(Class<?> modelClass) {
    if (detailsProviderService == null) {
      throw new IllegalStateException("DetailsProviderService is null");
    }
    if (detailsProviderService.getDialect(JsonSchemaDetailsService.class) == null) {
      throw new IllegalStateException("Form Schema Generator has not been initialized");
    }
    JsonSchemaGenerator generator = detailsProviderService.getDialect(JsonSchemaGenerator.class);
    if (generator == null) {
      throw new IllegalStateException("JSON Schema Generator has not been initialized");
    }
    ObjectNode schema = generator.generateSchema(modelClass);
    JsonNode properties = schema.get("properties");
    if (properties instanceof ObjectNode propertiesNode) {
      List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(propertiesNode.properties());
      fields.sort(Comparator.comparingInt(entry -> {
        JsonNode order = entry.getValue().get("x-order");
        return order == null ? Integer.MAX_VALUE : order.asInt();
      }));
      ObjectNode sorted = mapper.createObjectNode();
      fields.forEach(entry -> sorted.set(entry.getKey(), entry.getValue()));
      schema.set("properties", sorted);
    }
    FieldPermissionPolicy.applySchema(modelClass, schema);
    return schema;
  }

  public Set<Map<String, Object>> buttonSchemas(Class<?> modelClass, AuthorizationContext context) {
    ButtonDeclarations declarations = modelClass.getAnnotation(ButtonDeclarations.class);
    if (declarations == null || context == null) {
      return Set.of();
    }
    Collection<String> resources = context.getResources() == null ? Set.of() : context.getResources();
    boolean administrator = Boolean.TRUE.equals(context.getIsAdministrator());
    Set<Map<String, Object>> result = new LinkedHashSet<>();
    for (ButtonDeclaration declaration : declarations.value()) {
      if (administrator || resources.contains(declaration.authority())) {
        result.add(annotationAttributes(declaration));
      }
    }
    return result;
  }

  public Map<String, Object> annotationAttributes(Annotation annotation) {
    Map<String, Object> result = new HashMap<>();
    for (Method method : annotation.annotationType().getDeclaredMethods()) {
      try {
        result.put(method.getName(), method.invoke(annotation));
      } catch (ReflectiveOperationException exception) {
        log.warn("Could not extract annotation attribute {}", method.getName(), exception);
      }
    }
    return result;
  }
}
