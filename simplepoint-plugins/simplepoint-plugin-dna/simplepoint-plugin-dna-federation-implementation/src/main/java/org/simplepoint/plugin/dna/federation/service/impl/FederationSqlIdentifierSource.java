package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.calcite.sql.SqlIdentifier;

/**
 * Original spelling and quote semantics; Calcite's normalized names are not physical identities.
 */
final class FederationSqlIdentifierSource {
  private final String sql;
  private final List<FederationSqlTokens.Token> tokens;
  private final Map<Integer, Integer> positions = new HashMap<>();

  FederationSqlIdentifierSource(String sql) {
    this.sql = sql;
    tokens = FederationSqlTokens.read(sql);
    for (int index = 0; index < tokens.size(); index++) {
      positions.put(tokens.get(index).start(), index);
    }
  }

  Reference capture(SqlIdentifier identifier) {
    if (identifier.isStar() || identifier.names.isEmpty() || identifier.names.size() > 4) {
      throw unsupported();
    }
    var names = new ArrayList<String>();
    var quoted = new ArrayList<Boolean>();
    int start = -1;
    int prefixEnd = -1;
    int previous = -1;
    for (int component = 0; component < identifier.names.size(); component++) {
      int offset =
          FederationSqlSourcePosition.offset(sql, identifier.getComponentParserPosition(component));
      Integer index = positions.get(offset);
      if (index == null
          || component > 0 && (index != previous + 2 || !tokens.get(previous + 1).is("."))) {
        throw unsupported();
      }
      var token = tokens.get(index);
      boolean quote = token.kind() == FederationSqlTokens.Kind.IDENTIFIER;
      // Calcite reconstructs some CTE names without component quote flags. The original token
      // remains authoritative for both spelling and quoting; the AST supplies only its location.
      if (token.value().isEmpty() || !quote && token.kind() != FederationSqlTokens.Kind.WORD) {
        throw unsupported();
      }
      names.add(token.value());
      quoted.add(quote);
      if (component == 0) {
        start = offset;
      } else if (component == 1) {
        prefixEnd = offset;
      }
      previous = index;
    }
    return new Reference(names, quoted, start, prefixEnd);
  }

  /**
   * A CTE can hide a physical input only when the original reference has unambiguous binding.
   * Quoted/unquoted mixing and Unicode case changes can bind differently in native SQL and Calcite.
   */
  String bindingKey(SqlIdentifier identifier) {
    var reference = capture(identifier);
    if (reference.names().size() != 1) {
      throw unsupported();
    }
    String name = reference.names().getFirst();
    if (reference.quoted().getFirst()) {
      return "quoted:" + name;
    }
    return "unquoted:"
        + (name.chars().allMatch(value -> value < 128) ? name.toLowerCase(Locale.ROOT) : name);
  }

  record Reference(List<String> names, List<Boolean> quoted, int start, int prefixEnd) {
    Reference {
      names = List.copyOf(names);
      quoted = List.copyOf(quoted);
    }
  }
}
