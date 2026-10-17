package org.simplepoint.plugin.dna.runtime.service.schema;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;

/** Preserves fractional precision omitted by Calcite's stock MySQL TIMESTAMP-to-DATETIME cast. */
final class MysqlRuntimeRecordSqlDialect extends MysqlSqlDialect {

  MysqlRuntimeRecordSqlDialect(final Context context) {
    super(RuntimeJsonSql.context(context));
  }

  @Override
  public SqlNode getCastSpec(final RelDataType type) {
    if (type.getSqlTypeName() == SqlTypeName.ANY) {
      return null;
    }
    if (type.getSqlTypeName() != SqlTypeName.TIMESTAMP || type.getPrecision() < 0) {
      return super.getCastSpec(type);
    }
    return new SqlDataTypeSpec(new SqlBasicTypeNameSpec(SqlTypeName.TIMESTAMP, type.getPrecision(), SqlParserPos.ZERO) {
      @Override
      public void unparse(final SqlWriter writer, final int leftPrec, final int rightPrec) {
        writer.keyword("DATETIME");
        final SqlWriter.Frame frame = writer.startList("(", ")");
        writer.print(type.getPrecision());
        writer.endList(frame);
      }
    }, SqlParserPos.ZERO);
  }

  @Override
  public void unparseCall(final SqlWriter writer, final SqlCall call, final int leftPrec, final int rightPrec) {
    if (call.getKind() == SqlKind.JSON_VALUE_EXPRESSION || call.getKind() == SqlKind.JSON_TYPE) {
      RuntimeJsonSql.cast(writer, call.operand(0), "JSON");
    } else if (call.getOperator().getName().equals("JSON_VALUE")) {
      RuntimeJsonSql.requireRootPath(call);
      final SqlWriter.Frame unquote = writer.startFunCall("JSON_UNQUOTE");
      final SqlWriter.Frame extract = writer.startFunCall("JSON_EXTRACT");
      call.operand(0).unparse(writer, 0, 0);
      writer.sep(",");
      call.operand(1).unparse(writer, 0, 0);
      writer.endFunCall(extract);
      writer.endFunCall(unquote);
    } else {
      super.unparseCall(writer, call, leftPrec, rightPrec);
    }
  }
}
