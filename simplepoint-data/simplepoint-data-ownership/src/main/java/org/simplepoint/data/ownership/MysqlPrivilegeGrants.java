package org.simplepoint.data.ownership;

import static org.simplepoint.data.ownership.MysqlTriggerMetadataAccess.unavailable;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded interpretation of server-produced SHOW GRANTS, never executable SQL or user policy. Role
 * activation and scope matching belong to the adapter; column-only grants are never table grants.
 */
final class MysqlPrivilegeGrants {
  enum Privilege {
    TRIGGER,
    SELECT
  }

  record Scope(boolean revoked, String database, String table, boolean granted) {}

  private MysqlPrivilegeGrants() {}

  static List<Scope> triggers(List<String> grants) throws PhysicalOwnershipException {
    return forPrivilege(grants, Privilege.TRIGGER);
  }

  static List<Scope> forPrivilege(List<String> grants, Privilege privilege)
      throws PhysicalOwnershipException {
    if (grants.isEmpty() || grants.size() > 512) {
      throw unavailable();
    }
    var scopes = new ArrayList<Scope>();
    for (String grant : grants) {
      var tokens = tokens(grant);
      boolean revoked = tokens.getFirst().is("REVOKE");
      if (!revoked && !tokens.getFirst().is("GRANT")) {
        throw unavailable();
      }
      int on = -1;
      int depth = 0;
      boolean granted = false;
      for (int index = 1; index < tokens.size(); index++) {
        var token = tokens.get(index);
        if (depth == 0 && token.is("ON")) {
          on = index;
          break;
        }
        if (token.is("(")) {
          depth++;
        } else if (token.is(")")) {
          if (--depth < 0) {
            throw unavailable();
          }
        } else if (depth == 0 && (token.is(privilege.name()) || token.is("ALL"))) {
          // SELECT (column) does not prove complete column metadata visibility.
          if (index + 1 >= tokens.size() || !tokens.get(index + 1).is("(")) {
            granted = true;
          }
        }
      }
      if (!granted && (revoked || on < 0)) {
        continue;
      }
      if (on + 1 >= tokens.size()) {
        throw unavailable();
      }
      if (!granted
          && (tokens.get(1).is("PROXY")
              || tokens.get(on + 1).is("FUNCTION")
              || tokens.get(on + 1).is("PROCEDURE"))) {
        continue;
      }
      if (on < 0
          || on + 5 >= tokens.size()
          || !tokens.get(on + 2).is(".")
          || !tokens.get(on + 4).is(revoked ? "FROM" : "TO")) {
        throw unavailable();
      }
      String database = scopeName(tokens.get(on + 1));
      String table = scopeName(tokens.get(on + 3));
      if (database == null && table != null || revoked && table != null) {
        throw unavailable();
      }
      // A database row without the requested privilege can precede and shadow a matching wildcard
      // grant.
      if (granted || database != null && table == null) {
        scopes.add(new Scope(revoked, database, table, granted));
      }
    }
    return List.copyOf(scopes);
  }

  private static String scopeName(Token token) throws PhysicalOwnershipException {
    if (token.is("*")) {
      return null;
    }
    if (!token.quoted() || token.value().isEmpty() || token.value().length() > 255) {
      throw unavailable();
    }
    return token.value();
  }

  private record Token(String value, boolean quoted) {
    boolean is(String value) {
      return !quoted && this.value.equalsIgnoreCase(value);
    }
  }

  private static List<Token> tokens(String sql) throws PhysicalOwnershipException {
    if (sql == null || sql.isBlank() || sql.length() > 65536 || sql.indexOf('\0') >= 0) {
      throw unavailable();
    }
    var tokens = new ArrayList<Token>();
    for (int index = 0; index < sql.length(); ) {
      char value = sql.charAt(index);
      if (Character.isWhitespace(value)) {
        index++;
        continue;
      }
      if (value == '`' || value == '\'') {
        char quote = value;
        var decoded = new StringBuilder();
        boolean closed = false;
        for (index++; index < sql.length(); index++) {
          value = sql.charAt(index);
          if (value == quote) {
            if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
              decoded.append(quote);
              index++;
            } else {
              index++;
              closed = true;
              break;
            }
          } else {
            // SHOW GRANTS preserves database-pattern backslashes inside backtick identifiers.
            decoded.append(value);
          }
        }
        if (!closed) {
          throw unavailable();
        }
        tokens.add(new Token(decoded.toString(), true));
      } else if (Character.isLetter(value) || value == '_') {
        int start = index++;
        while (index < sql.length()
            && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_')) {
          index++;
        }
        tokens.add(new Token(sql.substring(start, index), false));
      } else {
        tokens.add(new Token(Character.toString(value), false));
        index++;
      }
      if (tokens.size() > 4096) {
        throw unavailable();
      }
    }
    return List.copyOf(tokens);
  }
}
