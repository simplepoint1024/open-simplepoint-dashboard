package org.simplepoint.core.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;

/** A page may contain several standard entities, such as a dictionary and its items. */
public record PermissionPageDescriptor(List<PermissionModelDescriptor> models) {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  public PermissionPageDescriptor { models = List.copyOf(models); }
  public boolean data() { return !models.isEmpty() && models.stream().allMatch(PermissionModelDescriptor::data); }
  public boolean fields() { return models.stream().anyMatch(PermissionModelDescriptor::fields); }
  public Set<String> fieldModels() {
    return models.stream().filter(PermissionModelDescriptor::fields).map(PermissionModelDescriptor::model).collect(Collectors.toSet());
  }

  /** Expressions use only fields with the same enforceable type/operator contract on every model. */
  public Map<String, PermissionModelDescriptor.Field> commonProperties() {
    Map<String, PermissionModelDescriptor.Field> result = new LinkedHashMap<>();
    if (models.isEmpty()) return result;
    result.putAll(models.getFirst().properties());
    for (var model : models) {
      result.entrySet().removeIf(entry -> {
        var field = model.properties().get(entry.getKey());
        return field == null || !boxed(entry.getValue().type()).equals(boxed(field.type()))
            || !Set.copyOf(entry.getValue().values()).equals(Set.copyOf(field.values()));
      });
      result.replaceAll((name, field) -> new PermissionModelDescriptor.Field(boxed(field.type()),
          field.title(), field.operators().stream().filter(model.properties().get(name).operators()::contains).toList(),
          field.values()));
    }
    result.entrySet().removeIf(entry -> entry.getValue().operators().isEmpty());
    return Map.copyOf(result);
  }

  private static String boxed(String type) {
    return switch (type) {
      case "boolean" -> "java.lang.Boolean";
      case "byte" -> "java.lang.Byte";
      case "short" -> "java.lang.Short";
      case "int" -> "java.lang.Integer";
      case "long" -> "java.lang.Long";
      case "float" -> "java.lang.Float";
      case "double" -> "java.lang.Double";
      default -> type;
    };
  }
  public String toJson() {
    try { return MAPPER.writeValueAsString(this); }
    catch (java.io.IOException error) { throw new IllegalStateException("Cannot serialize permission page", error); }
  }
  public static PermissionPageDescriptor fromJson(String json) {
    if (json == null || json.isBlank()) return null;
    try { return MAPPER.readValue(json, PermissionPageDescriptor.class); }
    catch (java.io.IOException error) { throw new IllegalStateException("Invalid registered permission page", error); }
  }
}
