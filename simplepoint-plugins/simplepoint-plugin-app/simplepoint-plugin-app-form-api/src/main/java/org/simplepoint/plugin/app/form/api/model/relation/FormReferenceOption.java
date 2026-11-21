package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authorized lookup projection; labels and displayed values are never accepted as write authority.
 */
public record FormReferenceOption(
    String id, long version, String label, Map<String, Object> values,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
    boolean canDrillThrough) {
  /** Old directory providers do not imply an action capability. */
  public FormReferenceOption(String id, long version, String label, Map<String, Object> values) {
    this(id, version, label, values, false);
  }

  public FormReferenceOption {
    values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
