package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact authorized read whose visible copied fields require original permission entrances. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordCurrentTargetsQuery(
    String contractVersion,
    OwnedRecordQuery record,
    RecordPageQuery page,
    RelatedRecordPageQuery related,
    @JsonProperty(required = true) int timeoutMs) {
  /** One bounded query only; no caller-provided provenance or authorization assertion. */
  public RecordCurrentTargetsQuery {
    if (!"1".equals(contractVersion)
        || (record == null ? 0 : 1) + (page == null ? 0 : 1) + (related == null ? 0 : 1) != 1
        || timeoutMs < 1
        || timeoutMs > 30_000) {
      throw new IllegalArgumentException("Invalid current record target query");
    }
  }

  /** Resolves the root identity from the one selected query. */
  public String physicalModelId() {
    return record != null
        ? record.parent().physicalModelId()
        : page != null ? page.physicalModelId() : related.records().physicalModelId();
  }

  /** Retains the schema observed by the owning APP runtime. */
  public RecordSchemaReference schemaReference() {
    return record != null
        ? record.parent().schemaReference()
        : page != null ? page.schemaReference() : related.records().schemaReference();
  }

  /** Shrinks an existing read deadline without changing selection semantics. */
  public RecordCurrentTargetsQuery withTimeout(int timeout) {
    return new RecordCurrentTargetsQuery(contractVersion, record, page, related, timeout);
  }
}
