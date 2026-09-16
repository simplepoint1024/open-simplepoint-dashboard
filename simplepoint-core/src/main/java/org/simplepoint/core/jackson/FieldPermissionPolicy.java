package org.simplepoint.core.jackson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.annotation.PermissionResource;

/** Shared field policy for serialization, writes, schemas and explicit map projections. */
public final class FieldPermissionPolicy {
  private FieldPermissionPolicy() { }

  public static Class<?> resourceType(Class<?> type) {
    while (org.hibernate.proxy.HibernateProxy.class.isAssignableFrom(type) && type.getSuperclass() != null) {
      type = type.getSuperclass();
    }
    PermissionResource alias = type.getAnnotation(PermissionResource.class);
    return alias == null ? type : alias.value();
  }

  public static String access(Class<?> type, String field) {
    return access(AuthorizationContextHolder.getContext(), type, field);
  }

  public static String access(org.simplepoint.core.AuthorizationContext context, Class<?> type, String field) {
    if (context == null || context.getFieldPermissions() == null) return null;
    Class<?> resource = resourceType(type);
    field = logicalField(resource, field);
    Map<String, String> permissions = context.getFieldPermissions();
    String canonical = permissions.get(resource.getName() + "#" + field);
    String legacy = permissions.get(resource.getSimpleName() + "#" + field);
    // An existing restrictive legacy policy must not be weakened by a second alias.
    return rank(canonical) >= rank(legacy) ? canonical : legacy;
  }

  private static int rank(String access) {
    if (access == null) return 0;
    return switch (access) {
      case "EDITABLE" -> 1;
      case "VISIBLE" -> 2;
      case "MASKED" -> 3;
      default -> 4;
    };
  }

  private static String logicalField(Class<?> resource, String jsonName) {
    for (Class<?> type = resource; type != null && type != Object.class; type = type.getSuperclass()) {
      for (var field : type.getDeclaredFields()) {
        var property = field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        if (property != null && property.value().equals(jsonName)) return field.getName();
      }
    }
    return jsonName;
  }

  public static boolean hidden(String access) {
    return access != null && !java.util.Set.of("EDITABLE", "VISIBLE", "MASKED").contains(access);
  }

  public static boolean writable(Class<?> type, String field) {
    return writable(AuthorizationContextHolder.getContext(), type, field);
  }

  public static boolean writable(org.simplepoint.core.AuthorizationContext context, Class<?> type, String field) {
    String access = access(context, type, field);
    return access == null || "EDITABLE".equals(access);
  }

  /** Raw maps have no domain identity; query/projection adapters must call this explicitly. */
  public static Map<String, Object> project(Class<?> resource, Map<String, ?> values) {
    Map<String, Object> result = new LinkedHashMap<>();
    values.forEach((field, value) -> {
      String access = access(resource, field);
      if (!hidden(access)) {
        result.put(field, "MASKED".equals(access) && value != null ? "***" : value);
      }
    });
    return result;
  }

  public static void applySchema(Class<?> resource, ObjectNode schema) {
    if (!(schema.get("properties") instanceof ObjectNode properties)) return;
    for (String field : java.util.List.copyOf(properties.properties().stream().map(Map.Entry::getKey).toList())) {
      String access = access(resource, field);
      if (access == null) continue;
      if (hidden(access)) {
        properties.remove(field);
        if (schema.get("required") instanceof ArrayNode required) {
          for (int i = required.size() - 1; i >= 0; i--) {
            if (field.equals(required.get(i).asText())) required.remove(i);
          }
        }
      } else if (properties.get(field) instanceof ObjectNode definition) {
        definition.put("x-field-access", access);
        if (!"EDITABLE".equals(access)) definition.put("readOnly", true);
        if ("MASKED".equals(access)) {
          definition.remove(java.util.List.of("default", "examples", "example", "enum", "const"));
        }
      }
    }
  }
}
