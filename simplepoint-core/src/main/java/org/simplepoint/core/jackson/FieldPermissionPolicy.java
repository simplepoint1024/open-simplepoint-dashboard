package org.simplepoint.core.jackson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.annotation.PermissionResource;

/** Shared field policy for serialization, writes, schemas and explicit map projections. */
public final class FieldPermissionPolicy {
  private FieldPermissionPolicy() {}

  /** Resolves proxies and projection aliases to the permission-owning entity. */
  public static Class<?> resourceType(Class<?> type) {
    while (org.hibernate.proxy.HibernateProxy.class.isAssignableFrom(type)
        && type.getSuperclass() != null) {
      type = type.getSuperclass();
    }
    PermissionResource alias = type.getAnnotation(PermissionResource.class);
    return alias == null ? type : alias.value();
  }

  /** Reads field access from the active request authorization context. */
  public static String access(Class<?> type, String field) {
    return access(AuthorizationContextHolder.getContext(), type, field);
  }

  /** Resolves canonical and legacy policies without weakening either restriction. */
  public static String access(
      org.simplepoint.core.AuthorizationContext context, Class<?> type, String field) {
    if (context == null) {
      return null;
    }
    Class<?> resource = resourceType(type);
    field = logicalField(resource, field);
    Map<String, String> permissions = context.activeFieldPermissions();
    if (permissions == null) permissions = context.getFieldPermissions();
    if (permissions == null) return null;
    String canonical = permissions.get(resource.getName() + "#" + field);
    String legacy = permissions.get(resource.getSimpleName() + "#" + field);
    // An existing restrictive legacy policy must not be weakened by a second alias.
    String result = rank(canonical) >= rank(legacy) ? canonical : legacy;
    for (Class<?> current = resource; current != null && current != Object.class; current = current.getSuperclass()) {
      try {
        var projection = current.getDeclaredField(field)
            .getAnnotation(org.simplepoint.core.annotation.PermissionFields.class);
        if (projection != null) {
          for (String source : projection.value()) {
            String inherited;
            if (projection.resource() != Void.class) {
              inherited = access(context, projection.resource(), source);
            } else {
              String owner = projection.resourceName();
              String simple = owner.substring(Math.max(owner.lastIndexOf('.'), owner.lastIndexOf('$')) + 1);
              String qualified = permissions.get(owner + "#" + source);
              String unqualified = permissions.get(simple + "#" + source);
              inherited = owner.isBlank() ? "HIDDEN" : rank(qualified) >= rank(unqualified) ? qualified : unqualified;
            }
            if (rank(inherited) > rank(result)) {
              result = inherited;
            }
          }
        }
        break;
      } catch (NoSuchFieldException ignored) {
        // Continue through inherited projection fields.
      }
    }
    return result;
  }

  private static int rank(String access) {
    if (access == null) {
      return 0;
    }
    return switch (access) {
      case "EDITABLE" -> 1;
      case "VISIBLE" -> 2;
      case "MASKED" -> 3;
      default -> 4;
    };
  }

  private static String logicalField(Class<?> resource, String jsonName) {
    for (Class<?> type = resource;
        type != null && type != Object.class;
        type = type.getSuperclass()) {
      for (var field : type.getDeclaredFields()) {
        var property = field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        if (property != null && property.value().equals(jsonName)) {
          return field.getName();
        }
      }
    }
    return jsonName;
  }

  /** Unknown permission levels are treated as hidden. */
  public static boolean hidden(String access) {
    return access != null && !java.util.Set.of("EDITABLE", "VISIBLE", "MASKED").contains(access);
  }

  /** Checks write access using the active request context. */
  public static boolean writable(Class<?> type, String field) {
    return writable(AuthorizationContextHolder.getContext(), type, field);
  }

  /** Checks explicit-context write access; absent policy retains the domain default. */
  public static boolean writable(
      org.simplepoint.core.AuthorizationContext context, Class<?> type, String field) {
    String access = access(context, type, field);
    return access == null || "EDITABLE".equals(access);
  }

  /** Raw maps have no domain identity; query/projection adapters must call this explicitly. */
  public static Map<String, Object> project(Class<?> resource, Map<String, ?> values) {
    Map<String, Object> result = new LinkedHashMap<>();
    values.forEach(
        (field, value) -> {
          String access = access(resource, field);
          if (!hidden(access)) {
            result.put(field, "MASKED".equals(access) && value != null ? "***" : value);
          }
        });
    return result;
  }

  /** Removes hidden fields and marks visible-only or masked form fields read-only. */
  public static void applySchema(Class<?> resource, ObjectNode schema) {
    if (!(schema.get("properties") instanceof ObjectNode properties)) {
      return;
    }
    for (String field :
        java.util.List.copyOf(properties.properties().stream().map(Map.Entry::getKey).toList())) {
      String access = access(resource, field);
      if (access == null) {
        continue;
      }
      if (hidden(access)) {
        properties.remove(field);
        if (schema.get("required") instanceof ArrayNode required) {
          for (int i = required.size() - 1; i >= 0; i--) {
            if (field.equals(required.get(i).asText())) {
              required.remove(i);
            }
          }
        }
      } else if (properties.get(field) instanceof ObjectNode definition) {
        definition.put("x-field-access", access);
        if (!"EDITABLE".equals(access)) {
          definition.put("readOnly", true);
        }
        if ("MASKED".equals(access)) {
          definition.remove(java.util.List.of("default", "examples", "example", "enum", "const"));
        }
      }
    }
  }
}
