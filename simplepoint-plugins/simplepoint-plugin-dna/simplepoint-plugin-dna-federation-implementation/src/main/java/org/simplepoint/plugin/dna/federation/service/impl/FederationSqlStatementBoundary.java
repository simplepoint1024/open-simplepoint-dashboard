package org.simplepoint.plugin.dna.federation.service.impl;

import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;

/** Bounded framing before recursive parsing; comments never become executable native SQL. */
final class FederationSqlStatementBoundary {
  private FederationSqlStatementBoundary() {}

  static void requireSingle(String sql) {
    if (sql == null || sql.isBlank() || sql.length() > FederationSqlAuditor.SQL_TEXT_MAX_LENGTH) {
      throw unsupported();
    }
    int depth = 0;
    boolean ended = false;
    boolean content = false;
    for (int index = 0; index < sql.length(); index++) {
      char value = sql.charAt(index);
      if (Character.isWhitespace(value)) {
        continue;
      }
      if (value == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
        // MySQL requires whitespace after --; accepting a different interpretation is unsafe.
        if (index + 2 < sql.length() && !Character.isWhitespace(sql.charAt(index + 2))) {
          throw unsupported();
        }
        index += 2;
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
          index++;
        }
        continue;
      }
      if (value == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
        if (index + 2 < sql.length()
            && (sql.charAt(index + 2) == '!'
                || index + 3 < sql.length()
                    && (sql.charAt(index + 2) == 'M' || sql.charAt(index + 2) == 'm')
                    && sql.charAt(index + 3) == '!')) {
          throw unsupported();
        }
        int end = sql.indexOf("*/", index + 2);
        // Nested comments differ across the supported native drivers. Do not reinterpret them.
        int nested = sql.indexOf("/*", index + 2);
        if (end < 0 || nested >= 0 && nested < end) {
          throw unsupported();
        }
        index = end + 1;
        continue;
      }
      if (ended || value == '\0') {
        throw unsupported();
      }
      if (value == ';') {
        if (depth != 0 || !content) {
          throw unsupported();
        }
        ended = true;
      } else if (value == '\'' || value == '"' || value == '`' || value == '[') {
        index = quoted(sql, index, value == '[' ? ']' : value);
        content = true;
      } else if (value == '(') {
        if (++depth > 64) {
          throw unsupported();
        }
        content = true;
      } else if (value == ')') {
        if (--depth < 0) {
          throw unsupported();
        }
      } else {
        content = true;
      }
    }
    if (!content || depth != 0) {
      throw unsupported();
    }
  }

  private static int quoted(String sql, int start, char quote) {
    for (int index = start + 1; index < sql.length(); index++) {
      char value = sql.charAt(index);
      // Native escape interpretation depends on server SQL mode. Parameters retain these values.
      if (value == '\\' || value == '\0') {
        throw unsupported();
      }
      if (value == quote) {
        if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
          index++;
        } else {
          return index;
        }
      }
    }
    throw unsupported();
  }

  static FederationSqlWriteException unsupported() {
    return new FederationSqlWriteException(Code.SQL_WRITE_UNSUPPORTED);
  }
}
