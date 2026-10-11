package org.simplepoint.plugin.workflow.service;

import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.core.schema.ProjectionTableSchema;
import org.simplepoint.data.jpa.ProjectionPages;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.springframework.data.domain.Pageable;

/** Workflow transport/localization adapter over the shared database projection contract. */
final class WorkflowReadPages {
  private WorkflowReadPages() {}

  static <R, T> PageResult<T> query(
      EntityManager em,
      Class<R> rowType,
      Class<T> outputType,
      Map<String, String> filters,
      Pageable requested,
      String defaultOrder,
      Set<String> ignoreCase,
      ProjectionPages.Boundary<R> boundary,
      Function<R, T> project) {
    var result =
        ProjectionPages.query(
            em,
            rowType,
            outputType,
            filters,
            requested,
            defaultOrder,
            ignoreCase,
            boundary,
            project);
    return new PageResult<>(
        result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements());
  }

  static Map<String, Object> schema(
      Class<?> type, String namespace, Map<String, List<String>> choices) {
    Map<String, List<ProjectionTableSchema.Choice>> translated = new LinkedHashMap<>();
    choices.forEach(
        (field, values) ->
            translated.put(
                field,
                values.stream()
                    .map(
                        value ->
                            new ProjectionTableSchema.Choice(
                                value, "i18n:workflow.status." + value))
                    .toList()));
    return ProjectionTableSchema.schema(type, namespace, translated);
  }
}
