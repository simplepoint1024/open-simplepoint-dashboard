package org.simplepoint.platform.composition.verification;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Owns one disposable schema; the supplied database must be a dedicated local test database. */
final class MigrationPostgresDatabase implements AutoCloseable {
  private final DriverManagerDataSource admin;
  private final DriverManagerDataSource source;
  private final String schema;

  MigrationPostgresDatabase() {
    this("migration_test");
  }

  MigrationPostgresDatabase(String database) {
    if (!java.util.Set.of(
            "migration_test", "migration_identity_test", "migration_installation_test")
        .contains(database)) {
      throw new IllegalArgumentException(
          "Only declared disposable migration databases are allowed");
    }
    String url = System.getenv("SP_MIGRATIONS_POSTGRES_URL");
    if (url == null
        || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/migration_test")) {
      throw new IllegalArgumentException(
          "Migration tests require an isolated localhost migration_test database");
    }
    url = url.substring(0, url.lastIndexOf('/') + 1) + database;
    String user = System.getenv("SP_MIGRATIONS_POSTGRES_USER");
    String password = System.getenv("SP_MIGRATIONS_POSTGRES_PASSWORD");
    admin = new DriverManagerDataSource(url, user, password);
    schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
    source = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password);
    new JdbcTemplate(admin).execute("create schema " + schema);
  }

  String schema() {
    return schema;
  }

  DriverManagerDataSource source() {
    return source;
  }

  JdbcTemplate jdbc() {
    return new JdbcTemplate(source);
  }

  List<Map<String, Object>> physicalContract(String pattern) {
    return jdbc()
        .queryForList(
            """
            select 'column' as kind, table_name as owner, column_name as name,
                   data_type || ':' || coalesce(character_maximum_length::text, '') || ':' ||
                   is_nullable || ':' || coalesce(column_default, '') as definition
              from information_schema.columns
             where table_schema=current_schema() and table_name like ?
            union all
            select 'constraint', c.relname, k.conname, pg_get_constraintdef(k.oid)
              from pg_constraint k join pg_class c on c.oid=k.conrelid
             where c.relnamespace=current_schema()::regnamespace and c.relname like ?
            union all
            select 'index', tablename, indexname, replace(indexdef, schemaname || '.', 'owned.')
              from pg_indexes
             where schemaname=current_schema() and tablename like ?
            order by kind, owner, name
            """,
            pattern,
            pattern,
            pattern);
  }

  @Override
  public void close() {
    new JdbcTemplate(admin).execute("drop schema if exists " + schema + " cascade");
  }
}
