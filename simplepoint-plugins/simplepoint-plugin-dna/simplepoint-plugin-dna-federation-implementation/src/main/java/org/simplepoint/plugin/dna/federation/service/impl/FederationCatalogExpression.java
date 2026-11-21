package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.config.Lex;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCharStringLiteral;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

/**
 * Parses server-deparsed expressions without evaluation, using the pinned Calcite Babel grammar.
 */
final class FederationCatalogExpression {
  private FederationCatalogExpression() {}

  static SqlNode parse(String expression, boolean postgres) {
    if (expression == null || expression.isBlank() || expression.length() > 65536) {
      throw unsupported();
    }
    // Reuse bounded token framing before entering a recursive parser. Catalog constants with native
    // escape forms are certified separately from their typed Const nodes, never evaluated here.
    FederationSqlStatementBoundary.requireSingle(expression);
    try {
      return SqlParser.create(
              postgres ? expression : mysqlKeywords(expression),
              SqlParser.config()
                  .withParserFactory(SqlBabelParserImpl.FACTORY)
                  .withLex(postgres ? Lex.ORACLE : Lex.MYSQL)
                  .withUnquotedCasing(Casing.UNCHANGED)
                  .withConformance(SqlConformanceEnum.BABEL))
          .parseExpression();
    } catch (SqlParseException ignored) {
      throw unsupported();
    }
  }

  private static String mysqlKeywords(String expression) {
    // Babel reserves UUID for a typed literal, whereas MySQL has UUID(). Quote only that exact
    // function token in the inspection copy; strings, field names and native SQL are unchanged.
    var tokens = FederationSqlTokens.read(expression);
    var normalized = new StringBuilder(expression);
    for (int index = tokens.size() - 2; index >= 0; index--) {
      var token = tokens.get(index);
      if (token.is("UUID") && tokens.get(index + 1).is("(")) {
        normalized.replace(token.start(), token.end(), "`UUID`");
      }
    }
    return normalized.toString();
  }

  static void verify(String expression, boolean postgres) {
    FederationWriteExpressionPolicy.verifyCatalog(parse(expression, postgres));
  }

  /** The caller separately proves exactly one sequence dependency owned by this same column. */
  static boolean ownedSequenceDefault(String expression) {
    SqlNode node = parse(expression, true);
    if (!(node instanceof SqlCall call)
        || !"nextval".equals(call.getOperator().getName())
        || call.operandCount() != 1
        || !(call.operand(0) instanceof SqlCall cast)
        || cast.getKind() != SqlKind.CAST
        || cast.operandCount() != 2
        || !(cast.operand(0) instanceof SqlCharStringLiteral)
        || !(cast.operand(1) instanceof SqlDataTypeSpec type)) {
      return false;
    }
    var name = type.getTypeName();
    return name.isSimple() && !name.isComponentQuoted(0) && "regclass".equals(name.getSimple());
  }
}
