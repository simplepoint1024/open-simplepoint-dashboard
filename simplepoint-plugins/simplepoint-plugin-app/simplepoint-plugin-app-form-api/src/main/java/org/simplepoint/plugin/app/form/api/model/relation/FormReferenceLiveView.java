package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.Map;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

/** Current authorized canonical values; not a write confirmation or an immutable snapshot. */
public record FormReferenceLiveView(
    String targetRecordId, Long targetVersion, Map<String, FormValue> values) {
  /** Missing fields stay missing, so revoked grants cannot be replaced by stale display values. */
  public FormReferenceLiveView {
    values = Map.copyOf(values);
    if (values.size() > 32
        || targetVersion != null && targetVersion < 0
        || targetRecordId == null
            && (targetVersion != null
                || values.values().stream().anyMatch(value -> value.kind() != FormValueKind.NULL))
        || targetRecordId != null
            && (!UUID.fromString(targetRecordId).toString().equals(targetRecordId)
                || targetVersion == null && !values.isEmpty())) {
      throw new IllegalArgumentException("Invalid live reference projection");
    }
    values.keySet().forEach(FormRelationIdentity::require);
  }
}
