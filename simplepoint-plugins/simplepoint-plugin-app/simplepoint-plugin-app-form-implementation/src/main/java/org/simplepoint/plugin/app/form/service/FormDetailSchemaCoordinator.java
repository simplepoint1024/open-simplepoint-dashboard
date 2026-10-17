package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.springframework.stereotype.Component;

/** Publishes child models through the same resumable DNA schema coordinator as the parent. */
@Component
public class FormDetailSchemaCoordinator {
  private final FormDnaSchemaPublicationCoordinator publications;
  private final FormStorageProjectionCompiler storage;
  private final ObjectMapper mapper;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public FormDetailSchemaCoordinator(
      FormDnaSchemaPublicationCoordinator publications,
      FormStorageProjectionCompiler storage,
      ObjectMapper mapper) {
    this.publications = publications;
    this.storage = storage;
    this.mapper = mapper;
  }

  /** Publishes child models through DNA and returns their immutable binding metadata. */
  public String prepare(
      DnaInvocationContext context,
      String applicationId,
      String formId,
      String parentModelId,
      ApplicationFormDocument document,
      boolean recover) {
    List<FormDetailBinding> bindings = new ArrayList<>();
    for (var detail : document.details()) {
      var projection = storage.compileDetail(detail, parentModelId);
      String childFormId =
          UUID.nameUUIDFromBytes(
                  (formId + ":detail:" + detail.detailId()).getBytes(StandardCharsets.UTF_8))
              .toString();
      var result =
          publications.prepare(
              context,
              new FormDnaSchemaPublicationCoordinator.DnaSchemaPreparationRequest(
                  applicationId,
                  childFormId,
                  detail.label(),
                  "default",
                  null,
                  projection.schema(),
                  projection.checksum(),
                  recover));
      if (!result.active()) {
        return null;
      }
      bindings.add(
          new FormDetailBinding(
              detail.detailId(),
              result.physicalModelId(),
              result.physicalModelVersion(),
              projection.checksum()));
    }
    try {
      return mapper.writeValueAsString(bindings);
    } catch (JsonProcessingException failure) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_FAILED,
          "Cannot encode detail schema bindings",
          failure);
    }
  }

  /** Reads persisted child bindings while preserving compatibility with forms without details. */
  public static List<FormDetailBinding> decode(String json, ObjectMapper mapper) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return mapper.readValue(json, new TypeReference<List<FormDetailBinding>>() {});
    } catch (JsonProcessingException failure) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PERSISTENCE_FAILED, "Invalid detail schema binding", failure);
    }
  }
}
