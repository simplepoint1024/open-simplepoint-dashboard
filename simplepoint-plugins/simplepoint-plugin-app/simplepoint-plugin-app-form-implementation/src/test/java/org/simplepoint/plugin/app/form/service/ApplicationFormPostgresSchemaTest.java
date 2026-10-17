package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.entity.ApplicationFormDraftEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPublicationEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormReleaseKeyEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormWritePolicyPublicationEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Optional real PostgreSQL gate for the production migration and Hibernate mappings. */
class ApplicationFormPostgresSchemaTest {

  @Test
  void productionMigrationMatchesHibernateMappings() throws Exception {
    String url = System.getenv("APP_FORM_TEST_POSTGRES_URL");
    assumeTrue(url != null && !url.isBlank(), "Real PostgreSQL form schema gate");
    String username = environmentOr("APP_FORM_TEST_POSTGRES_USER", "postgres");
    String password = environmentOr("APP_FORM_TEST_POSTGRES_PASSWORD", "postgres");
    String schema = "app_form_test_" + UUID.randomUUID().toString().replace("-", "");
    String migration = Files.readString(Path.of(
        System.getProperty("simplepoint.app-form.migration")
    ));
    String releaseMigration = Files.readString(Path.of(
        System.getProperty("simplepoint.app-form.release-migration")
    ));

    String generationMigration = Files.readString(Path.of(
        System.getProperty("simplepoint.app-form.resource-generation-migration")
    ));
    String policyMigration = Files.readString(Path.of(
        System.getProperty("simplepoint.app-form.policy-publication-migration")
    ));
    StandardServiceRegistry registry = null;
    try {
      try (var connection = DriverManager.getConnection(url, username, password);
           var statement = connection.createStatement()) {
        statement.execute("create schema " + schema);
        statement.execute("set search_path to " + schema);
        statement.execute("""
            create table simpoint_app_definitions (
              id varchar(36) primary key,
              tenant_id varchar(64) not null
            )
            """);
        statement.execute("""
            create table simpoint_app_pages (
              id varchar(36) primary key,
              tenant_id varchar(64) not null,
              application_id varchar(36) not null,
              kind varchar(12) not null,
              name varchar(120) not null,
              sort_order integer not null,
              status varchar(20) not null,
              created_at timestamp with time zone not null,
              updated_at timestamp with time zone not null
            )
            """);
        executeMigration(statement, migration);
        executeMigration(statement, migration);
        executeMigration(statement, releaseMigration);
        executeMigration(statement, releaseMigration);
        statement.executeUpdate("""
            insert into simpoint_app_definitions (id, tenant_id)
            values ('app-valid', 'tenant-a')
            """);
        statement.executeUpdate("""
            insert into simpoint_app_forms (
              id, tenant_id, application_id, code, name, status, created_at, updated_at
            ) values (
              'form-valid', 'tenant-a', 'app-valid', 'valid', 'Valid', 'DRAFT', now(), now()
            )
            """);
        statement.executeUpdate("""
            insert into simpoint_app_pages
              (id, tenant_id, application_id, kind, name, sort_order, status, created_at, updated_at)
            values ('page-valid', 'tenant-a', 'app-valid', 'MAIN', 'Page', 0, 'DRAFT', now(), now())
            """);
        statement.executeUpdate("""
            insert into simpoint_app_form_versions
              (id, tenant_id, form_id, version_number, document_json, checksum,
               storage_schema_json, storage_checksum, renderer_version, created_at)
            values ('version-valid', 'tenant-a', 'form-valid', 1, '{}', repeat('a', 64),
                    '{}', repeat('b', 64), '1', now())
            """);
        statement.executeUpdate("""
            insert into simpoint_app_form_page_bindings
              (id, tenant_id, application_id, page_id, form_id, form_version, scene_id,
               idempotency_key, status, resource_managed_key, created_at, updated_at)
            values ('intent-valid', 'tenant-a', 'app-valid', 'page-valid', 'form-valid', 1,
                    'main', 'original-key', 'DATA_PREPARING', 'page:page-valid', now(), now())
            """);
        statement.executeUpdate("""
            insert into simpoint_app_form_versions
              (id, tenant_id, form_id, version_number, document_json, checksum,
               storage_schema_json, storage_checksum, renderer_version, created_at)
            values ('version-newer', 'tenant-a', 'form-valid', 2, '{}', repeat('c', 64),
                    '{}', repeat('b', 64), '1', now())
            """);
        statement.executeUpdate("""
            insert into simpoint_app_form_page_bindings
              (id, tenant_id, application_id, page_id, form_id, form_version, scene_id,
               idempotency_key, status, resource_managed_key, created_at, updated_at)
            values ('intent-newer', 'tenant-a', 'app-valid', 'page-valid', 'form-valid', 2,
                    'main', 'newer-key', 'FAILED', 'page:page-valid', now() + interval '1 minute', now())
            """);
        // Upgrade replay must retain intents that have not reached DNA yet.
        executeMigration(statement, releaseMigration);
        executeMigration(statement, generationMigration);
        executeMigration(statement, generationMigration);
        executeMigration(statement, policyMigration);
        executeMigration(statement, policyMigration);
        statement.executeUpdate("""
            insert into simpoint_app_form_policy_publications
              (id, tenant_id, application_id, form_id, page_id, binding_id, governing_version,
               expected_version, target_version, command_json, status, created_at, updated_at)
            values ('policy-valid', 'tenant-a', 'app-valid', 'form-valid', 'page-valid', 'intent-valid', 1,
                    0, 1, '{}', 'PREPARED', now(), now())
            """);
        assertThatThrownBy(() -> statement.executeUpdate("""
            update simpoint_app_form_policy_publications set status = 'TARGET_READY' where id = 'policy-valid'
            """)).isInstanceOf(SQLException.class);
        statement.executeUpdate("""
            update simpoint_app_form_policy_publications set status = 'TARGET_READY', target_fingerprint = repeat('a', 64)
              where id = 'policy-valid'
            """);
        executeMigration(statement, policyMigration);
        try (var rows = statement.executeQuery("select status, target_version from simpoint_app_form_policy_publications")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("TARGET_READY");
          assertThat(rows.getLong(2)).isEqualTo(1);
        }
        assertThatThrownBy(() -> statement.executeUpdate("""
            insert into simpoint_app_form_policy_publications
              (id, tenant_id, application_id, form_id, page_id, binding_id, governing_version,
               expected_version, target_version, command_json, status, created_at, updated_at)
            values ('policy-overtake', 'tenant-a', 'app-valid', 'form-valid', 'page-valid', 'intent-newer', 2,
                    1, 2, '{}', 'PREPARED', now(), now())
            """)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> statement.executeUpdate("""
            update simpoint_app_form_policy_publications set tenant_id = 'tenant-b' where id = 'policy-valid'
            """)).isInstanceOf(SQLException.class);
        statement.executeUpdate("update simpoint_app_form_policy_publications set status = 'ACTIVE' where id = 'policy-valid'");
        statement.executeUpdate("update simpoint_app_forms set write_policy_version = 1 where id = 'form-valid'");
        executeMigration(statement, policyMigration);
        try (var rows = statement.executeQuery("select write_policy_version from simpoint_app_forms where id = 'form-valid'")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isEqualTo(1);
        }
        try (var rows = statement.executeQuery("""
            select status, idempotency_key, generation from simpoint_app_form_page_bindings
             where id = 'intent-valid'
            """)) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("DATA_PREPARING");
          assertThat(rows.getString(2)).isEqualTo("original-key");
          assertThat(rows.getLong(3)).isEqualTo(1);
        }
        try (var rows = statement.executeQuery("""
            select generation from simpoint_app_form_page_bindings where id = 'intent-newer'
            """)) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isEqualTo(2);
        }
        // Future assignments and changed timestamps must not be renumbered by upgrade replay.
        statement.executeUpdate("""
            update simpoint_app_form_page_bindings set generation = 9, created_at = now() - interval '1 day'
              where id = 'intent-newer'
            """);
        executeMigration(statement, generationMigration);
        try (var rows = statement.executeQuery("""
            select generation from simpoint_app_form_page_bindings where id = 'intent-newer'
            """)) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isEqualTo(9);
        }
        assertThatThrownBy(() -> statement.executeUpdate("""
            update simpoint_app_form_page_bindings set generation = 1 where id = 'intent-newer'
            """)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> statement.executeUpdate("""
            update simpoint_app_form_page_bindings set generation = 0 where id = 'intent-newer'
            """)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> statement.executeUpdate("""
            insert into simpoint_app_forms (
              id, tenant_id, application_id, code, name, status, created_at, updated_at
            ) values (
              'form-invalid', 'tenant-b', 'app-valid', 'invalid', 'Invalid',
              'DRAFT', now(), now()
            )
            """))
            .isInstanceOf(SQLException.class);
      }

      verifyCancellationUpgrade(url, username, password, schema);
      registry = new StandardServiceRegistryBuilder()
          .applySetting("hibernate.connection.url", url)
          .applySetting("hibernate.connection.username", username)
          .applySetting("hibernate.connection.password", password)
          .applySetting("hibernate.default_schema", schema)
          .applySetting("hibernate.hbm2ddl.auto", "validate")
          .build();
      var factory = new MetadataSources(registry)
          .addAnnotatedClass(ApplicationFormEntity.class)
          .addAnnotatedClass(ApplicationFormDraftEntity.class)
          .addAnnotatedClass(ApplicationFormVersionEntity.class)
          .addAnnotatedClass(ApplicationFormPublicationEntity.class)
          .addAnnotatedClass(ApplicationFormPageBindingEntity.class)
          .addAnnotatedClass(ApplicationFormReleaseKeyEntity.class)
          .addAnnotatedClass(ApplicationFormWritePolicyPublicationEntity.class)
          .buildMetadata()
          .buildSessionFactory();
      try (factory; var session = factory.openSession()) {
        final var transaction = session.beginTransaction();
        session.createNativeMutationQuery("SET TRANSACTION READ ONLY").executeUpdate();
        var context = mock(FormRequestContext.class);
        when(context.requireActor()).thenReturn(new FormRequestContext.FormActor("tenant-a", "reader"));
        var state = new ApplicationFormPublicationStateService(session, context, mock(FormStorageProjectionCompiler.class),
            mock(FormDocumentValidator.class), mock(FormDocumentCodec.class), java.time.Clock.systemUTC(),
            new org.simplepoint.plugin.app.form.configuration.ApplicationFormPublicationProperties());
        assertThat(state.getForVersion("app-valid", "form-valid", 1)).isNull();
        transaction.commit();
      }
      try (var connection = DriverManager.getConnection(url, username, password);
           var statement = connection.prepareStatement("""
               select data_type
                 from information_schema.columns
                where table_schema = ?
                  and table_name = 'simpoint_app_form_drafts'
                  and column_name = 'document_json'
               """)) {
        statement.setString(1, schema);
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("text");
        }
      }
    } finally {
      if (registry != null) {
        StandardServiceRegistryBuilder.destroy(registry);
      }
      try (var connection = DriverManager.getConnection(url, username, password);
           var statement = connection.createStatement()) {
        statement.execute("drop schema if exists " + schema + " cascade");
      }
    }
  }

  @Test
  void freshBootstrapUsesThePlatformSqlInitializer() throws Exception {
    String url = System.getenv("APP_FORM_TEST_POSTGRES_URL");
    assumeTrue(url != null && !url.isBlank(), "Real PostgreSQL form schema gate");
    String username = environmentOr("APP_FORM_TEST_POSTGRES_USER", "postgres");
    String password = environmentOr("APP_FORM_TEST_POSTGRES_PASSWORD", "postgres");
    String schema = "app_bootstrap_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.createStatement()) {
      statement.execute("create schema " + schema);
      statement.execute("set search_path to " + schema);
      try {
        var resource = new FileSystemResource(System.getProperty("simplepoint.app-form.bootstrap-schema"));
        ScriptUtils.executeSqlScript(connection, resource);
        ScriptUtils.executeSqlScript(connection, resource);
        try (var rows = statement.executeQuery("""
            select column_name from information_schema.columns
             where table_schema = current_schema() and table_name = 'simpoint_app_forms' and column_name = 'write_policy_version'
            """)) {
          assertThat(rows.next()).isTrue();
        }
        try (var rows = statement.executeQuery("select count(*) from simpoint_app_form_policy_publications")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isZero();
        }
      } finally {
        statement.execute("drop schema " + schema + " cascade");
      }
    }
  }

  private static void verifyCancellationUpgrade(final String url, final String username, final String password,
      final String schema) throws Exception {
    String migration = Files.readString(Path.of(System.getProperty("simplepoint.app-form.policy-cancellation-migration")));
    try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.createStatement()) {
      statement.execute("set search_path to " + schema);
      executeMigration(statement, migration);
      executeMigration(statement, migration);
      try (var keys = statement.executeQuery("select binding_id from simpoint_app_form_release_keys where request_key = 'original-key'")) {
        assertThat(keys.next()).isTrue();
        assertThat(keys.getString(1)).isEqualTo("intent-valid");
      }
      assertThatThrownBy(() -> statement.executeUpdate("""
          insert into simpoint_app_form_release_keys (id, tenant_id, request_key, binding_id, created_at)
          values ('cross-tenant-key', 'tenant-b', 'foreign-key', 'intent-valid', now())
          """)).isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertRetry(statement)).isInstanceOf(SQLException.class);
      statement.executeUpdate("update simpoint_app_form_page_bindings set status = 'CANCELLED' where id = 'intent-newer'");
      insertRetry(statement);
      statement.executeUpdate("""
          insert into simpoint_app_form_policy_publications
            (id, tenant_id, application_id, form_id, page_id, binding_id, governing_version,
             expected_version, target_version, command_json, status, created_at, updated_at)
          values ('policy-cancel', 'tenant-a', 'app-valid', 'form-valid', 'page-valid', 'intent-newer', 2,
                  1, 2, '{}', 'CANCELLING', now(), now())
          """);
      assertThatThrownBy(() -> insertNextPolicy(statement)).isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.executeUpdate("""
          update simpoint_app_form_policy_publications set status = 'CANCELLED', target_fingerprint = repeat('b', 64)
            where id = 'policy-cancel'
          """)).isInstanceOf(SQLException.class);
      statement.executeUpdate("""
          update simpoint_app_form_policy_publications set status = 'CANCELLED', target_fingerprint = repeat('b', 64),
            cancellation_json = '{"cancelled":true}' where id = 'policy-cancel'
          """);
      insertNextPolicy(statement);
      String protocol = Files.readString(Path.of(System.getProperty("simplepoint.app-form.policy-protocol-migration")));
      executeMigration(statement, protocol);
      executeMigration(statement, protocol);
      try (var rows = statement.executeQuery("select policy_protocol_version from simpoint_app_form_page_bindings where id = 'intent-newer'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(1);
      }
      assertThatThrownBy(() -> statement.executeUpdate(
          "update simpoint_app_form_page_bindings set policy_protocol_version = 2 where id = 'intent-newer'"))
          .isInstanceOf(SQLException.class);
      executeMigration(statement, migration);
      try (var keys = statement.executeQuery("select binding_id from simpoint_app_form_release_keys where request_key = 'newer-key'")) {
        assertThat(keys.next()).isTrue();
        assertThat(keys.getString(1)).isEqualTo("intent-newer");
      }
    }
  }

  private static void insertRetry(final java.sql.Statement statement) throws SQLException {
    statement.executeUpdate("""
        insert into simpoint_app_form_page_bindings
          (id, tenant_id, application_id, page_id, form_id, form_version, scene_id, generation,
           idempotency_key, status, resource_managed_key, created_at, updated_at)
        values ('intent-retry', 'tenant-a', 'app-valid', 'page-valid', 'form-valid', 2, 'main', 10,
                'retry-key', 'DATA_PREPARING', 'page:page-valid', now(), now())
        """);
  }

  private static void insertNextPolicy(final java.sql.Statement statement) throws SQLException {
    statement.executeUpdate("""
        insert into simpoint_app_form_policy_publications
          (id, tenant_id, application_id, form_id, page_id, binding_id, governing_version,
           expected_version, target_version, command_json, status, created_at, updated_at)
        values ('policy-retry', 'tenant-a', 'app-valid', 'form-valid', 'page-valid', 'intent-retry', 2,
                2, 3, '{}', 'PREPARED', now(), now())
        """);
  }

  private static String environmentOr(final String name, final String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static void executeMigration(
      final java.sql.Statement statement,
      final String migration
  ) throws SQLException {
    // Let PostgreSQL parse the complete script, including comments and quoted semicolons.
    statement.execute(migration);
  }
}
