package org.simplepoint.data.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Read-only physical contract for explicitly owned PostgreSQL tables during legacy adoption. */
public final class PostgresSchemaContract {
  private PostgresSchemaContract() {}

  /** Captures structure, including constraints and indexes; never reads business column values. */
  public static Snapshot capture(Connection connection, List<String> tables) throws SQLException {
    if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
        || tables.isEmpty() || tables.size() != Set.copyOf(tables).size()
        || tables.stream().anyMatch(name -> !name.matches("[a-z][a-z0-9_]{0,62}"))) {
      throw new IllegalArgumentException("Invalid PostgreSQL adoption scope");
    }
    String namespace = connection.getSchema();
    if (namespace == null || !namespace.matches("[a-z][a-z0-9_]{0,62}")) {
      throw new IllegalArgumentException("Invalid PostgreSQL adoption namespace");
    }
    var rows = new ArrayList<String>();
    for (String table : tables.stream().sorted().toList()) {
      collect(connection, rows, namespace, table, """
          select 'table', c.relkind::text, c.relpersistence::text,
                 c.relrowsecurity::text, c.relforcerowsecurity::text,
                 coalesce(array_to_string(c.reloptions, ','), '')
          from pg_class c join pg_namespace n on n.oid=c.relnamespace
          where n.nspname=? and c.relname=?
          """);
      collect(connection, rows, namespace, table, """
          select 'column', a.attname, format_type(a.atttypid,a.atttypmod),
                 a.attnotnull::text, coalesce(pg_get_expr(d.adbin,d.adrelid), ''),
                 a.attidentity::text, a.attgenerated::text,
                 coalesce(coll.collname, '')
          from pg_class c join pg_namespace n on n.oid=c.relnamespace
          join pg_attribute a on a.attrelid=c.oid
          left join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
          left join pg_collation coll on coll.oid=a.attcollation
          where n.nspname=? and c.relname=? and a.attnum>0 and not a.attisdropped
          """);
      collect(connection, rows, namespace, table, """
          select 'constraint', k.contype::text, pg_get_constraintdef(k.oid),
                 k.convalidated::text, k.condeferrable::text, k.condeferred::text
          from pg_constraint k join pg_class c on c.oid=k.conrelid
          join pg_namespace n on n.oid=c.relnamespace
          where n.nspname=? and c.relname=? and k.contype<>'n'
          """);
      collect(connection, rows, namespace, table, """
          select 'index', i.indisunique::text, i.indisprimary::text,
                 i.indisvalid::text, i.indisready::text,
                 regexp_replace(pg_get_indexdef(i.indexrelid),
                   '^CREATE (UNIQUE )?INDEX [^ ]+ ON [^ ]+ USING ', '')
          from pg_index i join pg_class c on c.oid=i.indrelid
          join pg_namespace n on n.oid=c.relnamespace where n.nspname=? and c.relname=?
          """);
      collect(connection, rows, namespace, table, """
          select 'trigger', pg_get_triggerdef(t.oid), t.tgenabled::text
          from pg_trigger t join pg_class c on c.oid=t.tgrelid
          join pg_namespace n on n.oid=c.relnamespace
          where n.nspname=? and c.relname=? and not t.tgisinternal
          """);
      collect(connection, rows, namespace, table, """
          select 'policy', p.polname, p.polcmd::text, p.polpermissive::text,
                 p.polroles::text, coalesce(pg_get_expr(p.polqual,p.polrelid), ''),
                 coalesce(pg_get_expr(p.polwithcheck,p.polrelid), '')
          from pg_policy p join pg_class c on c.oid=p.polrelid
          join pg_namespace n on n.oid=c.relnamespace where n.nspname=? and c.relname=?
          """);
    }
    rows.sort(String::compareTo);
    return new Snapshot(List.copyOf(rows), hash(rows));
  }

  private static void collect(Connection connection, List<String> rows, String namespace,
      String table, String sql) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, namespace);
      statement.setString(2, table);
      try (var result = statement.executeQuery()) {
        int count = result.getMetaData().getColumnCount();
        while (result.next()) {
          var row = new StringBuilder();
          append(row, table);
          String kind = result.getString(1);
          for (int index = 1; index <= count; index++) {
            String value = result.getString(index);
            append(row, ("constraint".equals(kind) && index == 3) || ("index".equals(kind) && index == 6)
                ? normalizeConstraint(value) : value);
          }
          rows.add(row.toString());
        }
      }
    }
  }

  /**
   * Dump/restore distributes a varchar-array cast over its literal elements. Canonicalize only
   * that exact cast, including inside composite constraints and partial-index predicates. Leave
   * operators, literal order and all other SQL unchanged; never rewrite inside quoted SQL text.
   */
  static String normalizeConstraint(String definition) {
    // PostgreSQL deparsing normally uses ordinary SQL literals. Fail closed for escape syntax.
    if (definition.indexOf('\\') >= 0) {
      return definition;
    }
    var result = new StringBuilder();
    int cursor = 0;
    while (cursor < definition.length()) {
      char current = definition.charAt(cursor);
      if (current == '\'' || current == '"') {
        int end = cursor + 1;
        while (end < definition.length()) {
          if (definition.charAt(end++) == current) {
            if (end < definition.length() && definition.charAt(end) == current) {
              end++;
            } else {
              break;
            }
          }
        }
        result.append(definition, cursor, end);
        cursor = end;
      } else if (definition.startsWith("ARRAY[", cursor)
          && (cursor == 0 || !Character.isJavaIdentifierPart(definition.charAt(cursor - 1)))) {
        int next = cursor + 6;
        var literals = new ArrayList<String>();
        var parser = RESTORED_ARRAY_LITERAL.matcher(definition);
        while (next < definition.length()) {
          parser.region(next, definition.length());
          if (!parser.lookingAt()) {
            break;
          }
          literals.add(parser.group(1));
          next = parser.end();
          if (definition.startsWith("]", next)) {
            result.append("(ARRAY[").append(String.join(", ", literals)).append("])::text[]");
            cursor = next + 1;
            break;
          }
          if (!definition.startsWith(", ", next)) {
            break;
          }
          next += 2;
        }
        if (cursor != next + 1) {
          result.append(current);
          cursor++;
        }
      } else {
        result.append(current);
        cursor++;
      }
    }
    return result.toString();
  }

  private static final java.util.regex.Pattern RESTORED_ARRAY_LITERAL = java.util.regex.Pattern.compile(
      "\\(('(?:[^']|'')*'::character varying)\\)::text");

  private static void append(StringBuilder row, String value) {
    if (value == null) {
      row.append("-1:");
    } else {
      row.append(value.length()).append(':').append(value);
    }
  }

  private static String hash(List<String> rows) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String row : rows) {
        digest.update((row.length() + ":" + row).getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** Deterministic, length-framed contract and its integrity digest. */
  public record Snapshot(List<String> rows, String fingerprint) {
    /** Rejects a supplied digest that does not describe the supplied rows. */
    public Snapshot {
      rows = List.copyOf(rows);
      if (!rows.stream().sorted().toList().equals(rows) || !hash(rows).equals(fingerprint)) {
        throw new IllegalArgumentException("Invalid schema contract fingerprint");
      }
    }
  }
}
