package org.simplepoint.plugin.dna.runtime.service.record;

import org.apache.calcite.rel.type.RelDataTypeSystemImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;

/** Retains Runtime temporal precision and bounded JSON text when Calcite generates target SQL casts. */
public final class RuntimeRecordTypeSystem extends RelDataTypeSystemImpl {
  /** Reflectively constructed by the trusted Runtime catalog, never selected by a record request. */
  public RuntimeRecordTypeSystem() {
  }

  @Override
  public int getMaxPrecision(final SqlTypeName typeName) {
    return switch (typeName) {
      case TIME, TIME_WITH_LOCAL_TIME_ZONE, TIME_TZ, TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE, TIMESTAMP_TZ -> 6;
      case VARCHAR -> LogicalJsonValues.MAX_STORED_TEXT_LENGTH;
      default -> super.getMaxPrecision(typeName);
    };
  }
}
