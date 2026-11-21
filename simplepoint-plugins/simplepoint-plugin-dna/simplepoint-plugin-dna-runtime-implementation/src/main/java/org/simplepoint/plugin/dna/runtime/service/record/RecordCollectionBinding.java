package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;

/**
 * Validates resolved storage participants; publication and authorization are separate admission.
 */
record RecordCollectionBinding(
    RuntimeRecordModel edge, RuntimeRecordModel source, RuntimeRecordModel target) {
  RecordCollectionBinding {
    Objects.requireNonNull(edge, "edge");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    RecordSchemaCompatibility.requireCompatible(edge.requestedSchema(), edge.activeSchema());
    RecordSchemaCompatibility.requireCompatible(source.requestedSchema(), source.activeSchema());
    RecordSchemaCompatibility.requireCompatible(target.requestedSchema(), target.activeSchema());
    var collection = edge.activeSchema().collection();
    if (collection == null
        || edge.physicalModelId().equals(source.physicalModelId())
        || edge.physicalModelId().equals(target.physicalModelId())
        || !edge.appId().equals(source.appId())
        || !edge.formId().equals(collection.modelFormId(source.formId()))) {
      throw RecordReferenceAdmission.incompatible();
    }
    endpoint(edge, source, collection.sourceReferenceId());
    endpoint(edge, target, collection.targetReferenceId());
    if (source.physicalModelId().equals(target.physicalModelId()) && !source.equals(target)) {
      throw RecordReferenceAdmission.incompatible();
    }
    if (!source.physicalModelId().equals(target.physicalModelId())
        && source.target().metadataTarget().equals(target.target().metadataTarget())) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  String relationId() {
    return edge.activeSchema().collection().relationId();
  }

  String sourceColumn() {
    return column(edge.activeSchema().collection().sourceReferenceId());
  }

  String targetColumn() {
    return column(edge.activeSchema().collection().targetReferenceId());
  }

  private String column(String referenceId) {
    String field = reference(edge, referenceId).fieldId();
    return edge.activeSchema().fields().stream()
        .filter(value -> value.fieldId().equals(field))
        .findFirst()
        .orElseThrow(RecordReferenceAdmission::incompatible)
        .code();
  }

  private static LogicalReferenceDefinition reference(RuntimeRecordModel edge, String referenceId) {
    return edge.activeSchema().references().stream()
        .filter(value -> value.relationId().equals(referenceId))
        .findFirst()
        .orElseThrow(RecordReferenceAdmission::incompatible);
  }

  private static void endpoint(
      RuntimeRecordModel edge, RuntimeRecordModel endpoint, String referenceId) {
    var location = edge.target().metadataTarget();
    var resolved = endpoint.target().metadataTarget();
    if (endpoint.activeSchema().owner() != null
        || endpoint.activeSchema().collection() != null
        || !Boolean.TRUE.equals(endpoint.activeSchema().tenantRecordIdentity())
        || !reference(edge, referenceId).targetModelId().equals(endpoint.physicalModelId())
        || location.equals(resolved)
        || !resolved.equals(edge.target().referenceTargets().get(endpoint.physicalModelId()))
        || !location.dataSourceId().equals(resolved.dataSourceId())
        || !Objects.equals(location.catalogName(), resolved.catalogName())
        || !Objects.equals(location.schemaName(), resolved.schemaName())) {
      throw RecordReferenceAdmission.incompatible();
    }
  }
}
