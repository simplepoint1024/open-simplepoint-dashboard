package org.simplepoint.plugin.dna.runtime.api.model;

/** Server-owned cache invalidation target for one published physical table. */
public record RuntimeMetadataTarget(
    String dataSourceId,
    String catalogName,
    String schemaName,
    String tableName
) {

  /** Validates required server-owned target identity. */
  public RuntimeMetadataTarget {
    if (dataSourceId == null || dataSourceId.isBlank()) {
      throw new IllegalArgumentException("dataSourceId is required");
    }
    if (tableName == null || tableName.isBlank()) {
      throw new IllegalArgumentException("tableName is required");
    }
    dataSourceId = dataSourceId.trim();
    catalogName = catalogName == null ? "" : catalogName.trim();
    schemaName = schemaName == null ? "" : schemaName.trim();
    tableName = tableName.trim();
  }
}
