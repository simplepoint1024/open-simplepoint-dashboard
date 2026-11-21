package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;

/** Immutable binding for a pure association model, stored separately from owned detail models. */
public record FormCollectionSchemaBinding(
    String relationId, String physicalModelId, RecordSchemaReference schema) {
  public FormCollectionSchemaBinding {
    Objects.requireNonNull(relationId, "relationId");
    Objects.requireNonNull(physicalModelId, "physicalModelId");
    Objects.requireNonNull(schema, "schema");
  }

  static List<FormCollectionSchemaBinding> decode(String json, ObjectMapper mapper) {
    if (json == null) {
      return List.of();
    }
    try {
      return List.copyOf(
          mapper.readValue(json, new TypeReference<List<FormCollectionSchemaBinding>>() {}));
    } catch (JsonProcessingException | NullPointerException failure) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PERSISTENCE_FAILED,
          "Invalid collection schema bindings",
          failure);
    }
  }
}
