package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** An explicit mapping of saved target records for a newly owned collection. */
public record FormCollectionReplacement(
    String sourceRelationId, Map<String, String> targetRecords) {
  public static final int MAX_TARGET_RECORDS = 16_000;

  public FormCollectionReplacement {
    FormRelationIdentity.require(sourceRelationId);
    if (targetRecords == null || targetRecords.size() > MAX_TARGET_RECORDS) {
      throw new IllegalArgumentException("A bounded collection replacement mapping is required");
    }
    var ordered = new TreeMap<String, String>();
    targetRecords.forEach(
        (source, target) -> ordered.put(requireRecordId(source), requireRecordId(target)));
    targetRecords = Collections.unmodifiableMap(ordered);
  }

  private static String requireRecordId(String id) {
    if (id == null || id.length() != 36 || !UUID.fromString(id).toString().equals(id)) {
      throw new IllegalArgumentException("A canonical saved record identity is required");
    }
    return id;
  }
}
