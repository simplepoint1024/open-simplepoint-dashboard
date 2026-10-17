package org.simplepoint.plugin.dna.runtime.service.schema;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.type.SqlTypeName;

/** Renders standard Calcite JSON value expressions using PostgreSQL's native JSONB storage. */
final class PostgresqlRuntimeRecordSqlDialect extends PostgresqlSqlDialect {
  PostgresqlRuntimeRecordSqlDialect(final Context context) {
    super(RuntimeJsonSql.context(context));
  }

  @Override
  public SqlNode getCastSpec(final RelDataType type) {
    return type.getSqlTypeName() == SqlTypeName.ANY ? null : super.getCastSpec(type);
  }

  @Override
  public void unparseCall(final SqlWriter writer, final SqlCall call, final int leftPrec, final int rightPrec) {
    if (call.getKind() == SqlKind.JSON_VALUE_EXPRESSION || call.getKind() == SqlKind.JSON_TYPE) {
      RuntimeJsonSql.cast(writer, call.operand(0), "JSONB");
    } else if (call.getOperator().getName().equals("JSON_TYPE")) {
      SqlWriter.Frame upper = writer.startFunCall("UPPER");
      SqlWriter.Frame type = writer.startFunCall("jsonb_typeof");
      call.operand(0).unparse(writer, 0, 0);
      writer.endFunCall(type);
      writer.endFunCall(upper);
    } else if (call.getOperator().getName().equals("JSON_VALUE")) {
      RuntimeJsonSql.requireRootPath(call);
      final SqlWriter.Frame frame = writer.startList("(", ")");
      call.operand(0).unparse(writer, 0, 0);
      writer.keyword("#>>");
      writer.literal("'{}'");
      writer.endList(frame);
    } else {
      super.unparseCall(writer, call, leftPrec, rightPrec);
    }
  }
}
