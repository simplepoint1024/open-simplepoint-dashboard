package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.util.ArrayList;
import java.util.List;

/** Original-source tokens; quoted identifiers and literals are never treated as keywords. */
final class FederationSqlTokens {
  enum Kind {
    WORD,
    IDENTIFIER,
    STRING,
    NUMBER,
    SYMBOL
  }

  record Token(Kind kind, String value, int start, int end) {
    boolean is(String expected) {
      return (kind == Kind.WORD || kind == Kind.SYMBOL) && value.equalsIgnoreCase(expected);
    }
  }

  private FederationSqlTokens() {}

  static List<Token> read(String sql) {
    FederationSqlStatementBoundary.requireSingle(sql);
    var result = new ArrayList<Token>();
    for (int index = 0; index < sql.length(); ) {
      char value = sql.charAt(index);
      if (Character.isWhitespace(value)) {
        index++;
        continue;
      }
      if (sql.startsWith("--", index)) {
        while (index < sql.length() && sql.charAt(index) != '\r' && sql.charAt(index) != '\n') {
          index++;
        }
        continue;
      }
      if (sql.startsWith("/*", index)) {
        index = sql.indexOf("*/", index + 2) + 2;
        continue;
      }
      int start = index;
      if (value == '\'' || value == '"' || value == '`' || value == '[') {
        char close = value == '[' ? ']' : value;
        var decoded = new StringBuilder();
        index++;
        while (index < sql.length()) {
          char next = sql.charAt(index++);
          if (next == close) {
            if (index < sql.length() && sql.charAt(index) == close) {
              decoded.append(close);
              index++;
            } else {
              break;
            }
          } else {
            decoded.append(next);
          }
        }
        result.add(
            new Token(
                value == '\'' ? Kind.STRING : Kind.IDENTIFIER, decoded.toString(), start, index));
      } else if (Character.isUnicodeIdentifierStart(sql.codePointAt(index)) || value == '_') {
        index += Character.charCount(sql.codePointAt(index));
        while (index < sql.length()
            && (Character.isUnicodeIdentifierPart(sql.codePointAt(index))
                || sql.charAt(index) == '$')) {
          index += Character.charCount(sql.codePointAt(index));
        }
        result.add(new Token(Kind.WORD, sql.substring(start, index), start, index));
      } else if (value >= '0' && value <= '9') {
        while (index < sql.length() && sql.charAt(index) >= '0' && sql.charAt(index) <= '9') {
          index++;
        }
        result.add(new Token(Kind.NUMBER, sql.substring(start, index), start, index));
      } else {
        index++;
        result.add(new Token(Kind.SYMBOL, sql.substring(start, index), start, index));
      }
      if (result.size() > 4096) {
        throw unsupported();
      }
    }
    if (!result.isEmpty() && result.getLast().is(";")) {
      result.removeLast();
    }
    return List.copyOf(result);
  }
}
