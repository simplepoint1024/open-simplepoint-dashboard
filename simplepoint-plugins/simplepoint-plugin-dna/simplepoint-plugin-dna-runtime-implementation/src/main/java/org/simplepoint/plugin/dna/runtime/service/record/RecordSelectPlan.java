package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One bounded parameterized SELECT plus its authorized projected value fields. */
record RecordSelectPlan(
    String sql,
    List<Object> parameters,
    List<String> valueFields,
    int maxRows
) {

  RecordSelectPlan {
    parameters = parameters == null
        ? List.of() : Collections.unmodifiableList(new ArrayList<>(parameters));
    valueFields = valueFields == null ? List.of() : List.copyOf(valueFields);
  }
}
