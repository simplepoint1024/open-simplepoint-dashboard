package org.simplepoint.core.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.core.query.QueryField;

/** Serializable module contract used by role grants, expression validation and field editors. */
public record PermissionModelDescriptor(String model, boolean data, boolean fields,
    String owner, String department, Map<String, Field> properties) {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public record Field(String type, String title, List<String> operators, List<String> values, boolean readOnly) {
    public Field(String type, String title, List<String> operators, List<String> values) {
      this(type, title, operators, values, false);
    }
  }

  public PermissionModelDescriptor {
    properties = properties == null ? Map.of() : Map.copyOf(properties);
  }

  public static PermissionModelDescriptor describe(Class<?> model) {
    PermissionModel policy = model.getAnnotation(PermissionModel.class);
    String owner = policy == null ? "createdBy" : policy.owner();
    String department = policy == null ? "createOrgDeptId" : policy.department();
    Map<String, QueryField> queryFields = new LinkedHashMap<>();
    var query = QueryCapabilities.declaration(model);
    if (query != null) Arrays.stream(query.value()).forEach(f -> queryFields.put(f.value(), f));
    Map<String, Field> fields = new LinkedHashMap<>();
    for (Class<?> current = model; current != null && current != Object.class; current = current.getSuperclass()) {
      for (var field : current.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(jakarta.persistence.Transient.class)) continue;
        var json = field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        var ignored = field.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class);
        if (json != null && json.access() == com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY
            || ignored != null && ignored.value()) continue;
        Schema schema = field.getAnnotation(Schema.class);
        if (schema != null && (schema.accessMode() == Schema.AccessMode.WRITE_ONLY
            || schema.hidden() && !List.of("id", owner, department, "createdAt", "updatedAt").contains(field.getName()))) continue;
        Class<?> type = QueryCapabilities.scalarType(model, field.getName());
        if (!(type.isPrimitive() || type.isEnum() || type == String.class || Number.class.isAssignableFrom(type)
            || type == Boolean.class || type.getPackageName().equals("java.time") || type == java.util.UUID.class)) continue;
        QueryField definition = queryFields.get(field.getName());
        List<String> operators = definition == null ? List.of() : QueryCapabilities.operators(model, definition);
        if (List.of("id", owner, department).contains(field.getName()) && operators.isEmpty()) {
          operators = List.of("equals", "not:equals", "in", "not:in", "is:null", "is:not:null");
        }
        fields.putIfAbsent(field.getName(), new Field(type.getName(), schema == null || schema.title().isBlank()
            ? field.getName() : schema.title(), operators,
            type.isEnum() ? Arrays.stream(type.getEnumConstants()).map(value -> ((Enum<?>) value).name()).toList() : List.of(),
            schema != null && schema.accessMode() == Schema.AccessMode.READ_ONLY
                || json != null && json.access() == com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY));
      }
    }
    return new PermissionModelDescriptor(model.getName(), policy == null || policy.data(),
        policy == null || policy.fields(), owner, department, fields);
  }

  public String toJson() {
    try { return MAPPER.writeValueAsString(this); }
    catch (java.io.IOException error) { throw new IllegalStateException("Cannot serialize permission model", error); }
  }

  public static PermissionModelDescriptor fromJson(String json) {
    if (json == null || json.isBlank()) return null;
    try { return MAPPER.readValue(json, PermissionModelDescriptor.class); }
    catch (java.io.IOException error) { throw new IllegalStateException("Invalid registered permission model", error); }
  }
}
