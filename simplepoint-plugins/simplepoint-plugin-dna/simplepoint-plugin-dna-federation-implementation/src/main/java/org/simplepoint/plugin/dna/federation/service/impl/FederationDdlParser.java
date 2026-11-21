package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlPlan.ObjectKind;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlPlan.ObjectReference;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlPlan.Operation;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlPlan.Role;
import org.simplepoint.plugin.dna.federation.service.impl.FederationSqlTokens.Kind;
import org.simplepoint.plugin.dna.federation.service.impl.FederationSqlTokens.Token;

/** Bounded native DDL productions; embedded queries and expressions are parsed by Calcite. */
final class FederationDdlParser {
  private static final Set<String> TYPES =
      Set.of(
          "INT",
          "INTEGER",
          "BIGINT",
          "SMALLINT",
          "TINYINT",
          "MEDIUMINT",
          "SERIAL",
          "BIGSERIAL",
          "SMALLSERIAL",
          "DECIMAL",
          "NUMERIC",
          "REAL",
          "FLOAT",
          "DOUBLE",
          "BOOLEAN",
          "BOOL",
          "CHAR",
          "CHARACTER",
          "VARCHAR",
          "NCHAR",
          "NVARCHAR",
          "TEXT",
          "TINYTEXT",
          "MEDIUMTEXT",
          "LONGTEXT",
          "DATE",
          "TIME",
          "TIMESTAMP",
          "TIMESTAMPTZ",
          "DATETIME",
          "YEAR",
          "UUID",
          "BINARY",
          "VARBINARY",
          "BYTEA",
          "BLOB",
          "JSON",
          "JSONB",
          "BIT");
  private final String sql;
  private final List<Token> tokens;
  private final List<ObjectReference> objects = new ArrayList<>();
  private int position;
  private Operation operation;
  private boolean temporary;
  private boolean cascade;

  FederationDdlParser(String sql) {
    this.sql = sql;
    tokens = FederationSqlTokens.read(sql);
  }

  List<ObjectReference> parse() {
    statement();
    if (position != tokens.size() || objects.isEmpty()) {
      throw unsupported();
    }
    return List.copyOf(objects);
  }

  Operation operation() {
    return operation;
  }

  boolean temporary() {
    return temporary;
  }

  boolean cascade() {
    return cascade;
  }

  private void statement() {
    if (take("CREATE")) {
      operation = Operation.CREATE;
      create();
    } else if (take("ALTER")) {
      operation = Operation.ALTER;
      expect("TABLE");
      ifExists(false);
      take("ONLY");
      object(ObjectKind.TABLE, Role.TARGET);
      do {
        alter();
      } while (take(","));
    } else if (take("DROP")) {
      operation = Operation.DROP;
      ObjectKind kind = objectKind();
      take("CONCURRENTLY");
      ifExists(false);
      do {
        object(kind, Role.TARGET);
      } while (take(","));
      if (kind == ObjectKind.INDEX && take("ON")) {
        object(ObjectKind.TABLE, Role.INDEX_OWNER);
      }
      restriction();
    } else if (take("TRUNCATE")) {
      operation = Operation.TRUNCATE;
      take("TABLE");
      do {
        take("ONLY");
        object(ObjectKind.TABLE, Role.TARGET);
        take("*");
      } while (take(","));
      if (take("RESTART") || take("CONTINUE")) {
        expect("IDENTITY");
      }
      restriction();
    } else if (take("RENAME")) {
      operation = Operation.RENAME;
      expect("TABLE");
      do {
        object(ObjectKind.TABLE, Role.TARGET);
        expect("TO");
        object(ObjectKind.TABLE, Role.RENAME_TARGET);
      } while (take(","));
    } else if (take("COMMENT")) {
      operation = Operation.COMMENT;
      comment();
    } else {
      throw unsupported();
    }
  }

  private ObjectKind objectKind() {
    if (take("TABLE")) {
      return ObjectKind.TABLE;
    }
    if (take("VIEW")) {
      return ObjectKind.VIEW;
    }
    expect("INDEX");
    return ObjectKind.INDEX;
  }

  private void create() {
    boolean replace = take("OR");
    if (replace) {
      expect("REPLACE");
    }
    temporary = take("TEMP") || take("TEMPORARY");
    boolean unlogged = !temporary && take("UNLOGGED");
    boolean unique = take("UNIQUE");
    ObjectKind kind = objectKind();
    if (replace && kind != ObjectKind.VIEW
        || (temporary || unlogged) && kind != ObjectKind.TABLE
        || unique && kind != ObjectKind.INDEX) {
      throw unsupported();
    }
    if (kind == ObjectKind.INDEX) {
      take("CONCURRENTLY");
    }
    ifExists(true);
    object(kind, Role.TARGET);
    switch (kind) {
      case TABLE -> createTable();
      case VIEW -> {
        if (at("(")) {
          columns(false);
        }
        expect("AS");
        query();
      }
      case INDEX -> {
        expect("ON");
        take("ONLY");
        object(ObjectKind.TABLE, Role.INDEX_OWNER);
        if (take("USING")) {
          if (!(take("BTREE") || take("HASH"))) {
            throw unsupported();
          }
        }
        columns(true);
        if (take("INCLUDE")) {
          columns(false);
        }
        if (take("WHERE")) {
          expressionToEnd();
        }
      }
      default -> throw unsupported();
    }
  }

  private void createTable() {
    if (take("LIKE")) {
      object(ObjectKind.TABLE, Role.DEPENDENCY);
      return;
    }
    if (take("(")) {
      do {
        if (take("LIKE")) {
          object(ObjectKind.TABLE, Role.DEPENDENCY);
          while (take("INCLUDING") || take("EXCLUDING")) {
            if (!(take("ALL")
                || take("DEFAULTS")
                || take("CONSTRAINTS")
                || take("INDEXES")
                || take("IDENTITY")
                || take("GENERATED")
                || take("COMMENTS")
                || take("STATISTICS")
                || take("STORAGE"))) {
              throw unsupported();
            }
          }
        } else if (!constraint()) {
          column();
        }
      } while (take(","));
      expect(")");
      if (take("ON")) {
        expect("COMMIT");
        if (take("DELETE") || take("PRESERVE")) {
          expect("ROWS");
        } else {
          expect("DROP");
        }
      }
    } else if (!at("AS")) {
      throw unsupported();
    }
    if (take("AS")) {
      query();
    }
  }

  private void alter() {
    if (take("ADD")) {
      boolean column = take("COLUMN");
      if (column || !constraint()) {
        ifExists(true);
        column();
      }
    } else if (take("DROP")) {
      if (take("PRIMARY")) {
        expect("KEY");
      } else {
        if (take("FOREIGN")) {
          expect("KEY");
        } else if (!(take("CONSTRAINT") || take("INDEX") || take("KEY"))) {
          take("COLUMN");
        }
        ifExists(false);
        identifier();
      }
      restriction();
    } else if (take("RENAME")) {
      if (take("TO") || take("AS")) {
        object(ObjectKind.TABLE, Role.RENAME_TARGET);
      } else {
        if (!(take("COLUMN") || take("CONSTRAINT") || take("INDEX") || take("KEY"))) {
          throw unsupported();
        }
        identifier();
        expect("TO");
        identifier();
      }
    } else if (take("MODIFY")) {
      take("COLUMN");
      column();
    } else if (take("CHANGE")) {
      take("COLUMN");
      identifier();
      column();
    } else if (take("ALTER")) {
      take("COLUMN");
      identifier();
      if (take("TYPE")) {
        type();
      } else if (take("SET")) {
        if (take("DATA")) {
          expect("TYPE");
          type();
        } else if (take("DEFAULT")) {
          defaultValue();
        } else {
          expect("NOT");
          expect("NULL");
        }
      } else {
        expect("DROP");
        if (!take("DEFAULT")) {
          expect("NOT");
          expect("NULL");
        }
      }
    } else {
      throw unsupported();
    }
  }

  private boolean constraint() {
    boolean named = take("CONSTRAINT");
    if (named) {
      identifier();
    }
    if (take("PRIMARY")) {
      expect("KEY");
      columns(false);
    } else if (take("UNIQUE")) {
      take("KEY");
      if (!at("(")) {
        identifier();
      }
      columns(false);
    } else if (take("FOREIGN")) {
      expect("KEY");
      if (!at("(")) {
        identifier();
      }
      columns(false);
      expect("REFERENCES");
      reference();
    } else if (take("CHECK")) {
      expressionInParentheses();
    } else {
      if (named) {
        throw unsupported();
      }
      return false;
    }
    constraintTiming();
    return true;
  }

  private void column() {
    identifier();
    type();
    while (position < tokens.size() && !at(",") && !at(")")) {
      if (take("CONSTRAINT")) {
        identifier();
      }
      if (take("NOT")) {
        expect("NULL");
      } else if (take("NULL") || take("UNIQUE") || take("AUTO_INCREMENT")) {
        // These modifiers do not introduce another object reference.
      } else if (take("PRIMARY")) {
        expect("KEY");
      } else if (take("DEFAULT")) {
        defaultValue();
      } else if (take("REFERENCES")) {
        reference();
        constraintTiming();
      } else if (take("CHECK")) {
        expressionInParentheses();
      } else if (take("COMMENT")) {
        string();
      } else if (take("GENERATED")) {
        if (!take("ALWAYS")) {
          expect("BY");
          expect("DEFAULT");
        }
        expect("AS");
        if (!take("IDENTITY")) {
          expressionInParentheses();
          if (!take("STORED")) {
            expect("VIRTUAL");
          }
        }
      } else {
        throw unsupported();
      }
    }
  }

  private void type() {
    Token name = next();
    if (name.kind() != Kind.WORD || !TYPES.contains(name.value().toUpperCase(Locale.ROOT))) {
      throw unsupported();
    }
    if (name.is("DOUBLE")) {
      take("PRECISION");
    } else if (name.is("CHAR") || name.is("CHARACTER") || name.is("BIT")) {
      take("VARYING");
    }
    if (take("(")) {
      number();
      if (take(",")) {
        number();
      }
      expect(")");
    }
    if ((name.is("TIMESTAMP") || name.is("TIME")) && (take("WITH") || take("WITHOUT"))) {
      expect("TIME");
      expect("ZONE");
    }
    take("UNSIGNED");
  }

  private void reference() {
    object(ObjectKind.TABLE, Role.REFERENCE_TARGET);
    if (at("(")) {
      columns(false);
    }
    if (take("MATCH") && !(take("FULL") || take("SIMPLE") || take("PARTIAL"))) {
      throw unsupported();
    }
    boolean delete = false;
    boolean update = false;
    while (take("ON")) {
      if (take("DELETE") && !delete) {
        delete = true;
      } else if (take("UPDATE") && !update) {
        update = true;
      } else {
        throw unsupported();
      }
      if (take("NO")) {
        expect("ACTION");
      } else if (take("SET")) {
        if (!take("NULL")) {
          expect("DEFAULT");
        }
      } else if (!(take("RESTRICT") || take("CASCADE"))) {
        throw unsupported();
      }
    }
  }

  private void constraintTiming() {
    if (at("NOT") && position + 1 < tokens.size() && tokens.get(position + 1).is("DEFERRABLE")) {
      position += 2;
    } else {
      take("DEFERRABLE");
    }
    if (take("INITIALLY") && !(take("IMMEDIATE") || take("DEFERRED"))) {
      throw unsupported();
    }
    if (at("NOT") && position + 1 < tokens.size() && tokens.get(position + 1).is("VALID")) {
      position += 2;
    }
  }

  private void defaultValue() {
    if (at("(")) {
      expressionInParentheses();
    } else if (take("+") || take("-")) {
      numericLiteral();
    } else if (peek().kind() == Kind.NUMBER) {
      numericLiteral();
    } else if (peek().kind() == Kind.STRING) {
      string();
    } else if (take("NULL") || take("TRUE") || take("FALSE")) {
      // Literal defaults are not object names.
    } else if (take("CURRENT_TIMESTAMP") || take("CURRENT_TIME") || take("CURRENT_DATE")) {
      if (take("(")) {
        number();
        expect(")");
      }
    } else {
      throw unsupported();
    }
  }

  private void numericLiteral() {
    number();
    if (take(".")) {
      number();
    }
  }

  private void expressionInParentheses() {
    expect("(");
    int start = position;
    int depth = 1;
    while (depth > 0) {
      Token token = next();
      if (token.is("(")) {
        depth++;
      } else if (token.is(")")) {
        depth--;
      }
    }
    parsed(start, position - 1, false);
  }

  private void expressionToEnd() {
    parsed(position, tokens.size(), false);
    position = tokens.size();
  }

  private void query() {
    parsed(position, tokens.size(), true);
    position = tokens.size();
  }

  private void parsed(int start, int end, boolean query) {
    if (start >= end) {
      throw unsupported();
    }
    int offset = tokens.get(start).start();
    String fragment = sql.substring(offset, tokens.get(end - 1).end());
    try {
      var parser = SqlParser.create(fragment);
      var node = query ? parser.parseQuery() : parser.parseExpression();
      if (query && !node.isA(SqlKind.QUERY)) {
        throw unsupported();
      }
      FederationWriteExpressionPolicy.verify(node);
      var source = new FederationSqlIdentifierSource(fragment);
      var collector = new FederationSqlTableCollector(true, source);
      collector.walk(node, Set.of(), 0);
      for (SqlIdentifier table : collector.tables()) {
        var reference = source.capture(table);
        add(
            new ObjectReference(
                ObjectKind.TABLE,
                Role.DEPENDENCY,
                reference.names(),
                reference.quoted(),
                offset + reference.start(),
                reference.prefixEnd() < 0 ? -1 : offset + reference.prefixEnd()));
      }
    } catch (SqlParseException ignored) {
      throw unsupported();
    }
  }

  private void comment() {
    expect("ON");
    if (take("COLUMN")) {
      var parts = qualified();
      if (parts.size() < 2) {
        throw unsupported();
      }
      add(objectReference(ObjectKind.TABLE, Role.TARGET, parts.subList(0, parts.size() - 1)));
    } else {
      object(objectKind(), Role.TARGET);
    }
    expect("IS");
    if (!take("NULL")) {
      string();
    }
  }

  private void object(ObjectKind kind, Role role) {
    add(objectReference(kind, role, qualified()));
  }

  private void add(ObjectReference object) {
    if (objects.size() >= 256 || object.names().size() > 4) {
      throw unsupported();
    }
    objects.add(object);
  }

  private ObjectReference objectReference(ObjectKind kind, Role role, List<Token> parts) {
    return new ObjectReference(
        kind,
        role,
        parts.stream().map(Token::value).toList(),
        parts.stream().map(part -> part.kind() == Kind.IDENTIFIER).toList(),
        parts.getFirst().start(),
        parts.size() < 2 ? -1 : parts.get(1).start());
  }

  private List<Token> qualified() {
    var parts = new ArrayList<Token>();
    parts.add(identifier());
    while (take(".")) {
      parts.add(identifier());
      if (parts.size() > 5) {
        throw unsupported();
      }
    }
    return parts;
  }

  private void columns(boolean ordered) {
    expect("(");
    do {
      identifier();
      if (ordered && !take("ASC")) {
        take("DESC");
      }
    } while (take(","));
    expect(")");
  }

  private Token identifier() {
    Token token = next();
    if (token.value().isEmpty() || token.kind() != Kind.WORD && token.kind() != Kind.IDENTIFIER) {
      throw unsupported();
    }
    return token;
  }

  private void string() {
    if (next().kind() != Kind.STRING) {
      throw unsupported();
    }
  }

  private void number() {
    if (next().kind() != Kind.NUMBER) {
      throw unsupported();
    }
  }

  private void ifExists(boolean create) {
    if (take("IF")) {
      if (create) {
        expect("NOT");
      }
      expect("EXISTS");
    }
  }

  private void restriction() {
    if (!take("RESTRICT")) {
      cascade |= take("CASCADE");
    }
  }

  private Token next() {
    Token token = peek();
    position++;
    return token;
  }

  private Token peek() {
    if (position >= tokens.size()) {
      throw unsupported();
    }
    return tokens.get(position);
  }

  private boolean at(String word) {
    return position < tokens.size() && tokens.get(position).is(word);
  }

  private boolean take(String word) {
    if (!at(word)) {
      return false;
    }
    position++;
    return true;
  }

  private void expect(String word) {
    if (!take(word)) {
      throw unsupported();
    }
  }
}
