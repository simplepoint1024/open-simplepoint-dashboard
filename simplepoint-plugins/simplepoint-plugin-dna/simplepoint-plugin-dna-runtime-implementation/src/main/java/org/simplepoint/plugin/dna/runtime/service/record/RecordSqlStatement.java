package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;

/** Parameterized SQL generated only from trusted model metadata and structured requests. */
record RecordSqlStatement(
    String sql,
    List<Object> parameters,
    RecordMutationType mutationType,
    String recordId,
    long resultVersion,
    Instant updatedAt,
    Subject subject) {

  /** Infrastructure statements have no business subject. Only trusted compilers attach one. */
  RecordSqlStatement(
      String sql,
      List<Object> parameters,
      RecordMutationType mutationType,
      String recordId,
      long resultVersion,
      Instant updatedAt) {
    this(sql, parameters, mutationType, recordId, resultVersion, updatedAt, null);
  }

  RecordSqlStatement {
    parameters =
        parameters == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(parameters));
  }

  record Subject(String modelId, String tenantId, String actorId) {
    Subject(String modelId, String tenantId) {
      this(modelId, tenantId, null);
    }

    Subject {
      if (modelId == null || modelId.isBlank() || tenantId == null || tenantId.isBlank()) {
        throw new IllegalArgumentException("A business mutation requires its model and tenant");
      }
      if (actorId != null && (actorId.isBlank() || actorId.length() > 255)) {
        throw new IllegalArgumentException("A business mutation requires a bounded audit actor");
      }
    }
  }
}
