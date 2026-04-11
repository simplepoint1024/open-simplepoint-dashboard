package org.simplepoint.plugin.dna.federation.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.jupiter.api.Test;

class FederationSqlIdentifierNormalizerTest {

  @Test
  void normalizeReturnsNullForNullInput() {
    assertNull(FederationSqlIdentifierNormalizer.normalize(null));
  }

  @Test
  void normalizeReturnsNullForBlankInput() {
    assertNull(FederationSqlIdentifierNormalizer.normalize("   "));
  }

  @Test
  void normalizeReturnsSqlUnchangedWhenParsingFails() {
    String invalid = "NOT VALID SQL @@#!$%";
    assertEquals(invalid, FederationSqlIdentifierNormalizer.normalize(invalid));
  }

  @Test
  void normalizeQuotesTwoPartIdentifier() {
    String result = FederationSqlIdentifierNormalizer.normalize("select * from ds.orders");
    assertTrue(result.contains("\"ds\".\"orders\""), "Expected quoted 2-part identifier, got: " + result);
  }

  @Test
  void normalizeQuotesThreePartIdentifier() {
    String result = FederationSqlIdentifierNormalizer.normalize("select * from ds.public.orders");
    assertTrue(result.contains("\"ds\".\"public\".\"orders\""), "Expected quoted 3-part identifier, got: " + result);
  }

  @Test
  void normalizePreservesAlreadyQuotedIdentifiers() {
    String sql = "select * from \"ds\".\"public\".\"orders\"";
    assertEquals(sql, FederationSqlIdentifierNormalizer.normalize(sql));
  }

  @Test
  void normalizeDoesNotSplitDottedQuotedComponents() {
    String result = FederationSqlIdentifierNormalizer.normalize(
        "select * from ds.\"my.schema\".orders"
    );
    assertTrue(result.contains("\"my.schema\""), "Dotted quoted component should not be split, got: " + result);
  }

  @Test
  void normalizeHandlesSinglePartIdentifierUnchanged() {
    String sql = "select 1";
    assertEquals(sql, FederationSqlIdentifierNormalizer.normalize(sql));
  }

  @Test
  void normalizeHandlesStarInSelect() {
    String result = FederationSqlIdentifierNormalizer.normalize("select ds.orders.* from ds.orders");
    assertTrue(result.contains("\"ds\".\"orders\""), "Expected quoted table reference, got: " + result);
  }

  // ── unquoteIdentifier tests ──

  @Test
  void unquoteDoubleQuotedIdentifier() {
    assertEquals("my_table", FederationSqlIdentifierNormalizer.unquoteIdentifier("\"my_table\""));
  }

  @Test
  void unquoteDoubleQuotedWithEscapes() {
    assertEquals("my\"table", FederationSqlIdentifierNormalizer.unquoteIdentifier("\"my\"\"table\""));
  }

  @Test
  void unquoteBracketedIdentifier() {
    assertEquals("my_table", FederationSqlIdentifierNormalizer.unquoteIdentifier("[my_table]"));
  }

  @Test
  void unquoteBracketedWithEscapes() {
    assertEquals("my]table", FederationSqlIdentifierNormalizer.unquoteIdentifier("[my]]table]"));
  }

  @Test
  void unquoteBacktickIdentifier() {
    assertEquals("my_table", FederationSqlIdentifierNormalizer.unquoteIdentifier("`my_table`"));
  }

  @Test
  void unquoteBacktickWithEscapes() {
    assertEquals("my`table", FederationSqlIdentifierNormalizer.unquoteIdentifier("`my``table`"));
  }

  @Test
  void unquoteReturnsPlainIdentifierUnchanged() {
    assertEquals("my_table", FederationSqlIdentifierNormalizer.unquoteIdentifier("my_table"));
  }

  @Test
  void unquoteReturnsNullForNull() {
    assertNull(FederationSqlIdentifierNormalizer.unquoteIdentifier(null));
  }

  // ── quoteIdentifier tests ──

  @Test
  void quoteIdentifierUsesDoubleQuotes() {
    assertEquals("\"my_table\"", FederationSqlIdentifierNormalizer.quoteIdentifier("my_table"));
  }

  @Test
  void quoteIdentifierEscapesDoubleQuotes() {
    assertEquals("\"my\"\"table\"", FederationSqlIdentifierNormalizer.quoteIdentifier("my\"table"));
  }

  // ── needsNormalization tests ──

  @Test
  void needsNormalizationReturnsFalseForSingleComponent() {
    SqlIdentifier identifier = new SqlIdentifier("orders", SqlParserPos.ZERO);
    assertFalse(FederationSqlIdentifierNormalizer.needsNormalization(identifier));
  }

  @Test
  void needsNormalizationReturnsTrueForTwoUnquotedComponents() {
    SqlIdentifier identifier = new SqlIdentifier(List.of("ds", "orders"), SqlParserPos.ZERO);
    assertTrue(FederationSqlIdentifierNormalizer.needsNormalization(identifier));
  }

  @Test
  void needsNormalizationReturnsFalseForNull() {
    assertFalse(FederationSqlIdentifierNormalizer.needsNormalization(null));
  }
}
