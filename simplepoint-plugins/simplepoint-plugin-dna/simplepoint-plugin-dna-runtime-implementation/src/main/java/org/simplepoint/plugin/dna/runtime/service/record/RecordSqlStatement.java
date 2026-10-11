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
    Instant updatedAt
) {

  RecordSqlStatement {
    parameters = parameters == null
        ? List.of() : Collections.unmodifiableList(new ArrayList<>(parameters));
  }
}
