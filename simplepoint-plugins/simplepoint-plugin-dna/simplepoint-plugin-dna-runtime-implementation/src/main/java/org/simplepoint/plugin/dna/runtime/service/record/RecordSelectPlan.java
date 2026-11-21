package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One bounded parameterized SELECT plus its authorized projected value fields. */
record RecordSelectPlan(
    String sql,
    List<Object> parameters,
    List<String> valueFields,
    int maxRows,
    boolean physicalValueProjection,
    boolean physicalRelationalExecution) {

  RecordSelectPlan(
      String sql,
      List<Object> parameters,
      List<String> valueFields,
      int maxRows,
      boolean physicalValueProjection) {
    this(sql, parameters, valueFields, maxRows, physicalValueProjection, false);
  }

  RecordSelectPlan(
      final String sql,
      final List<Object> parameters,
      final List<String> valueFields,
      final int maxRows) {
    this(sql, parameters, valueFields, maxRows, false);
  }

  RecordSelectPlan {
    parameters =
        parameters == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(parameters));
    valueFields = valueFields == null ? List.of() : List.copyOf(valueFields);
  }

  /**
   * Collection cardinality/filter/order must remain in the owned database, even for small pages.
   */
  RecordSelectPlan requirePhysicalRelations() {
    return new RecordSelectPlan(
        sql, parameters, valueFields, maxRows, physicalValueProjection, true);
  }
}
