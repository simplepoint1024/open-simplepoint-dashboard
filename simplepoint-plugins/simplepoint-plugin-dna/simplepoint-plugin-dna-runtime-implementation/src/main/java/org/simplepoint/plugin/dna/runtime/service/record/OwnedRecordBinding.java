package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Shared aggregate ownership check for reads, writes and inverse parent selection. */
final class OwnedRecordBinding {
  private OwnedRecordBinding() {}

  static String parentCode(RuntimeRecordModel parent, RuntimeRecordModel child) {
    var owner = child.activeSchema().owner();
    var rootTarget = parent.target().metadataTarget();
    var childTarget = child.target().metadataTarget();
    if (owner == null
        || !owner.parentModelId().equals(parent.physicalModelId())
        || !Objects.equals(owner, child.requestedSchema().owner())
        || !child.appId().equals(parent.appId())
        || !rootTarget.dataSourceId().equals(childTarget.dataSourceId())
        || !Objects.equals(rootTarget.catalogName(), childTarget.catalogName())
        || !Objects.equals(rootTarget.schemaName(), childTarget.schemaName())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
          "Child does not belong to this aggregate target");
    }
    return child.requestedSchema().fields().stream()
        .filter(field -> field.fieldId().equals(owner.parentFieldId()))
        .findFirst()
        .orElseThrow(
            () ->
                new DnaRuntimeException(
                    DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE, "Parent reference is absent"))
        .code();
  }
}
