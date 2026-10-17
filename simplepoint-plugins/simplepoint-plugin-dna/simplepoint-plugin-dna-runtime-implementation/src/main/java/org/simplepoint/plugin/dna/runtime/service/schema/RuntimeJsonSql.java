package org.simplepoint.plugin.dna.runtime.service.schema;

import org.apache.calcite.rel.type.DelegatingTypeSystem;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCharStringLiteral;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.type.SqlTypeName;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;

/** AST-only JSON rendering shared by trusted Runtime SQL dialects. */
final class RuntimeJsonSql {
  private RuntimeJsonSql() {
  }

  static SqlDialect.Context context(final SqlDialect.Context context) {
    // SQL rendering has its own type system, independent of the query connection's validator.
    return context.withDataTypeSystem(new DelegatingTypeSystem(context.dataTypeSystem()) {
      @Override
      public int getMaxPrecision(final SqlTypeName type) {
        return type == SqlTypeName.VARCHAR || type == SqlTypeName.CHAR
            ? LogicalJsonValues.MAX_STORED_TEXT_LENGTH : super.getMaxPrecision(type);
      }
    });
  }

  static void requireRootPath(final SqlCall call) {
    if (!(call.operand(1) instanceof SqlCharStringLiteral path) || !"$".equals(path.getNlsString().getValue())) {
      throw new IllegalArgumentException("Runtime JSON scalar extraction requires the root path");
    }
  }

  static void cast(final SqlWriter writer, final SqlNode operand, final String type) {
    final SqlWriter.Frame frame = writer.startFunCall("CAST");
    operand.unparse(writer, 0, 0);
    writer.keyword("AS");
    writer.keyword(type);
    writer.endFunCall(frame);
  }
}
