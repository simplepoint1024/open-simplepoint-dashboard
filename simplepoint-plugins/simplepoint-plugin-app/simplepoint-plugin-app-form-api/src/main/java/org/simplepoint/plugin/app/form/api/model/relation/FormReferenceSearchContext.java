package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.Map;
import org.simplepoint.plugin.app.form.api.model.FormValue;

/** Uncommitted, typed candidate inputs. Organization and authorization are never browser inputs. */
public record FormReferenceSearchContext(
    Map<String, FormValue> fields, Map<String, FormValue> row) {
  /** Copies inputs and bounds the combined root/detail literal budget. */
  public FormReferenceSearchContext {
    fields = values(fields);
    row = values(row);
    if (fields.size() + row.size() > 16) {
      throw new IllegalArgumentException("Reference candidate input budget exceeded");
    }
  }

  private static Map<String, FormValue> values(Map<String, FormValue> values) {
    if (values == null) {
      return Map.of();
    }
    if (values.size() > 16) {
      throw new IllegalArgumentException("Reference candidate input budget exceeded");
    }
    values.keySet().forEach(FormRelationIdentity::require);
    values
        .values()
        .forEach(
            value -> {
              if (value == null || value.value() != null && value.value().length() > 2000) {
                throw new IllegalArgumentException(
                    "Reference candidate input exceeds its literal budget");
              }
            });
    return Map.copyOf(values);
  }
}
