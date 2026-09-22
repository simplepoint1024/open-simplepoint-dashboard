package org.simplepoint.core.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.jackson.FieldPermissionPolicy;

/** Shared schema/compiler policy; hidden and masked fields cannot be used as inference oracles. */
public final class QueryCapabilities {
  private QueryCapabilities() {}

  /** Resolves a read projection to the entity that owns its query policy. */
  public static Class<?> model(Class<?> type) {
    TableQuery query = type.getAnnotation(TableQuery.class);
    return query != null && query.model() != Void.class ? query.model() : type;
  }

  /** Returns an explicit opt-in declaration, or null for legacy domain adapters. */
  public static TableQuery declaration(Class<?> type) {
    if (type == null) {
      return null;
    }
    return model(type).getAnnotation(TableQuery.class);
  }

  /** Denies hidden and masked fields even if they occur in a static declaration. */
  public static boolean readable(Class<?> type, String name) {
    String access = FieldPermissionPolicy.access(model(type), name);
    return !FieldPermissionPolicy.hidden(access) && !"MASKED".equals(access);
  }

  /** Looks up only a named scalar member, including inherited entity properties. */
  public static Field field(Class<?> type, String name) {
    for (Class<?> current = model(type); current != null; current = current.getSuperclass()) {
      try {
        return current.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        /* inherited scalar */
      }
    }
    throw new InvalidQueryException("不支持的查询字段");
  }

  /** Resolves inherited generic members against the concrete entity, not their erased bound. */
  public static Class<?> scalarType(Class<?> type, String name) {
    return org.springframework.core.ResolvableType.forField(field(type, name), model(type))
        .resolve(field(type, name).getType());
  }

  /** Enforces the current field permission and declared operator/sort capability. */
  public static QueryField require(Class<?> type, String name, String operator, boolean sort) {
    TableQuery declaration = declaration(type);
    QueryField definition =
        declaration == null
            ? null
            : Arrays.stream(declaration.value())
                .filter(f -> f.value().equals(name))
                .findFirst()
                .orElse(null);
    if (definition == null
        || !readable(type, name)
        || (sort && !definition.sortable())
        || (!sort && !operators(type, definition).contains(operator))) {
      throw new InvalidQueryException("此字段或操作未开放查询");
    }
    return definition;
  }

  /** Intersects explicit operator restrictions with the scalar type's supported operators. */
  public static List<String> operators(Class<?> type, QueryField definition) {
    Class<?> scalar = scalarType(type, definition.value());
    if (scalar.isArray()
        || java.util.Collection.class.isAssignableFrom(scalar)
        || java.util.Map.class.isAssignableFrom(scalar)) {
      throw new IllegalStateException("TableQuery fields must be scalar");
    }
    boolean text = scalar == String.class;
    boolean comparable =
        text
            || Number.class.isAssignableFrom(scalar)
            || Set.of(int.class, long.class, short.class, byte.class, double.class, float.class)
                .contains(scalar)
            || scalar.getPackageName().equals("java.time")
            || java.util.Date.class.isAssignableFrom(scalar);
    List<String> typed =
        FilterCondition.OPERATORS.stream()
            .filter(op -> text || !op.contains("like"))
            .filter(op -> comparable || !(op.contains("between") || op.startsWith("than:")))
            .toList();
    return definition.operators().length == 0
        ? typed
        : typed.stream().filter(op -> List.of(definition.operators()).contains(op)).toList();
  }

  /** Adds the exact enforceable contract, without changing the form's field definitions. */
  public static void applySchema(Class<?> type, ObjectNode schema) {
    TableQuery declaration = declaration(type);
    if (declaration == null) {
      return;
    }
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> fields = new LinkedHashMap<>();
    for (QueryField f : declaration.value()) {
      if (!readable(type, f.value())) {
        continue;
      }
      Class<?> javaType = scalarType(type, f.value());
      Map<String, Object> capability = new LinkedHashMap<>();
      capability.put("operators", operators(type, f));
      capability.put("sortable", f.sortable());
      capability.put("javaType", javaType.getSimpleName());
      fields.put(f.value(), capability);
    }
    // Relations are advertised by alias only; nested type contracts remain server-controlled.
    List<String> relations =
        Arrays.stream(declaration.relations())
            .map(QueryRelation::value)
            .filter(name -> readable(type, name))
            .toList();
    schema.set(
        "x-query",
        mapper.valueToTree(
            Map.of(
                "version",
                1,
                "fields",
                fields,
                "relations",
                relations,
                "maxNodes",
                QueryDocument.MAX_NODES,
                "maxDepth",
                QueryDocument.MAX_DEPTH,
                "maxSort",
                3,
                "maxPageSize",
                declaration.maxPageSize())));
  }
}
