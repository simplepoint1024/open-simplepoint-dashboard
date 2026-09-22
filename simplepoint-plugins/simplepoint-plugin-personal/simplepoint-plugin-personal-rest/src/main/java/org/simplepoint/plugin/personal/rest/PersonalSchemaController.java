package org.simplepoint.plugin.personal.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.schema.AnnotatedTableSchemaProvider;
import org.simplepoint.plugin.personal.api.PersonalTableModels;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Uses the same permission-filtered annotation contract as resource management. */
@RestController
@RequestMapping("/personal")
@PreAuthorize("isAuthenticated()")
public class PersonalSchemaController {
  private final AnnotatedTableSchemaProvider schemas;
  private final ObjectMapper schemaMapper = new ObjectMapper();

  /** Reuses platform JSON Schema and button permission resolution. */
  public PersonalSchemaController(DetailsProviderService details) {
    schemas = new AnnotatedTableSchemaProvider(details);
  }

  private Map<String, Object> schema(Class<?> type) {
    var definition = schemas.jsonSchema(type);
    var properties = definition.withObject("/properties");
    for (String name : List.of("startAt", "dueAt", "createdAt")) {
      if (properties.has(name)) {
        var field = properties.withObject("/" + name);
        field.put("type", "string");
        field.put("format", "date-time");
        field.remove(List.of("$ref", "allOf", "anyOf", "properties", "required"));
      }
    }
    if (properties.has("checklist")) {
      properties.withObject("/checklist").put("maxItems", 50);
    }
    if (properties.has("priority")) {
      var priority = properties.withObject("/priority");
      priority.remove("enum");
      if (priority.has("default")) {
        priority.put("default", priority.path("default").asInt(1));
      }
      var choices = priority.putArray("oneOf");
      var labels = List.of("low", "normal", "high", "urgent");
      IntStream.range(0, labels.size())
          .forEach(
              value ->
                  choices
                      .addObject()
                      .put("const", value)
                      .put("title", "i18n:personal." + labels.get(value)));
    }
    if (type == PersonalTableModels.ItemTable.class && properties.has("status")) {
      var status = properties.withObject("/status");
      status.remove("enum");
      var statuses = status.putArray("oneOf");
      var values = List.of("TODO", "IN_PROGRESS", "DONE", "CANCELLED");
      var titles = List.of("notStarted", "inProgress", "done", "cancelled");
      IntStream.range(0, values.size())
          .forEach(
              value ->
                  statuses
                      .addObject()
                      .put("const", values.get(value))
                      .put("title", "i18n:personal." + titles.get(value)));
    }
    return Map.of(
        "schema",
        // The schema generator uses Jackson 2, while Spring MVC uses Jackson 3.
        // Keep the HTTP contract serializer-neutral, as the platform provider does.
        schemaMapper.convertValue(definition, new TypeReference<Map<String, Object>>() {}),
        "buttons",
        schemas.buttonSchemas(type, AuthorizationContextHolder.getContext()));
  }

  /** Personal item table/form metadata. */
  @GetMapping("/items/schema")
  public Response<Map<String, Object>> items() {
    return Response.okay(schema(PersonalTableModels.ItemTable.class));
  }

  /** Manual todo table/form metadata; workflow todos are not editable here. */
  @GetMapping("/todos/schema")
  public Response<Map<String, Object>> todos() {
    return Response.okay(schema(PersonalTableModels.TodoTable.class));
  }

  /** Message metadata has no create/edit/delete action. */
  @GetMapping("/messages/schema")
  public Response<Map<String, Object>> messages() {
    return Response.okay(schema(PersonalTableModels.MessageTable.class));
  }
}
