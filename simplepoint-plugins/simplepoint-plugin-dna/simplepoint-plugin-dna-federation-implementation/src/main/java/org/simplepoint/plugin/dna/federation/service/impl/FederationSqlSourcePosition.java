package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import org.apache.calcite.sql.parser.SqlParserPos;

/** Maps Calcite line/column positions back to the original, unnormalized SQL. */
final class FederationSqlSourcePosition {
  private FederationSqlSourcePosition() {}

  static int offset(String sql, SqlParserPos position) {
    int line = 1;
    int column = 1;
    for (int index = 0; index < sql.length(); index++) {
      if (line == position.getLineNum() && column == position.getColumnNum()) {
        return index;
      }
      char value = sql.charAt(index);
      if (value == '\r' || value == '\n') {
        if (value == '\r' && index + 1 < sql.length() && sql.charAt(index + 1) == '\n') {
          index++;
        }
        line++;
        column = 1;
      } else {
        column++;
      }
    }
    throw unsupported();
  }
}
