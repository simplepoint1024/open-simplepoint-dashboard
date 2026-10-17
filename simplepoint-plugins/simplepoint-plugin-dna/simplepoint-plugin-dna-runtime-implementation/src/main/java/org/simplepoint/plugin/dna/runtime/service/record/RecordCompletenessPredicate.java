package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSemantics;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;

/** Portable Calcite expression with the same whitespace semantics as form submission. */
final class RecordCompletenessPredicate {

  // ECMAScript WhiteSpace and LineTerminator, including NBSP, ideographic space and BOM.
  // U+0085 and U+200B are intentionally not empty. Removing characters, rather than nesting
  // TRIM calls, also handles arbitrary alternating runs of different whitespace characters.
  private static final String WHITESPACE = "\t\n\u000B\f\r \u00A0\u1680"
      + "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A"
      + "\u2028\u2029\u202F\u205F\u3000\uFEFF";

  private RecordCompletenessPredicate() {
  }

  static String compile(final String column, final LogicalFieldType type, final RecordSqlParameters parameters) {
    return compile(column, type, parameters, null);
  }

  static String compile(final String column, final LogicalFieldType type, final RecordSqlParameters parameters,
      final RecordJsonSemantics semantics) {
    String isNull = column + " IS NULL";
    if (type == LogicalFieldType.JSON && semantics == RecordJsonSemantics.STRUCTURAL_V1) {
      String nullJson = parameters.add(new RecordJsonParameter("null"));
      String object = parameters.add(new RecordJsonParameter("{}"));
      String array = parameters.add(new RecordJsonParameter("[]"));
      String text = "JSON_VALUE(" + column + ", '$' RETURNING VARCHAR(1048576))";
      return "(" + isNull + " OR " + column + " = " + nullJson + " OR " + column + " = " + object
          + " OR " + column + " = " + array + " OR CASE WHEN JSON_TYPE(" + column + ") = 'STRING' THEN "
          + compile(text, LogicalFieldType.STRING, parameters) + " ELSE FALSE END)";
    }
    if (type != LogicalFieldType.STRING && type != LogicalFieldType.TEXT) {
      return isNull;
    }
    String stripped = column;
    for (int index = 0; index < WHITESPACE.length(); index++) {
      String character = parameters.add(String.valueOf(WHITESPACE.charAt(index)));
      stripped = "REPLACE(" + stripped + ", CAST(" + character + " AS VARCHAR(1)), '')";
    }
    // CHAR_LENGTH avoids database collations equating non-whitespace strings to an empty string.
    return "(" + isNull + " OR CHAR_LENGTH(" + stripped + ") = 0)";
  }
}
