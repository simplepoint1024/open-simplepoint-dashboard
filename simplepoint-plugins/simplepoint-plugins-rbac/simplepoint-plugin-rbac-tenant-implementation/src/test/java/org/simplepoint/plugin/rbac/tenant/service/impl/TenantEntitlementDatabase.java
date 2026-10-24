package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

/** Isolated H2 or PostgreSQL schema; PostgreSQL executes the exact identity migration manifest. */
final class TenantEntitlementDatabase implements AutoCloseable {
  private static final String ROOT = "META-INF/simplepoint/modules/identity/db/";
  private final String url = System.getenv("SP_TENANT_PACKAGE_TEST_POSTGRES_URL");
  private final String user =
      System.getenv().getOrDefault("SP_TENANT_PACKAGE_TEST_POSTGRES_USER", "postgres");
  private final String password =
      System.getenv().getOrDefault("SP_TENANT_PACKAGE_TEST_POSTGRES_PASSWORD", "");
  private final String schema =
      "tenant_activation_" + UUID.randomUUID().toString().replace("-", "");
  private SessionFactory factory;
  private final MigrationFixture fixture;

  TenantEntitlementDatabase() {
    this((connection, migration) -> {});
  }

  TenantEntitlementDatabase(MigrationFixture fixture) {
    this.fixture = fixture;
  }

  @FunctionalInterface
  interface MigrationFixture {
    void before(Connection connection, String migration) throws SQLException;
  }

  SessionFactory create(Class<?>... entities) throws Exception {
    return createWithSettings(java.util.Map.of(), entities);
  }

  SessionFactory createWithSettings(java.util.Map<String, Object> additional, Class<?>... entities)
      throws Exception {
    var settings =
        new StandardServiceRegistryBuilder()
            .applySetting(
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
    if (url == null || url.isBlank()) {
      settings
          .applySetting("hibernate.connection.url", "jdbc:h2:mem:" + schema + ";LOCK_TIMEOUT=10000")
          .applySetting("hibernate.hbm2ddl.auto", "create-drop");
    } else {
      install();
      settings
          .applySetting("hibernate.connection.url", url)
          .applySetting("hibernate.connection.username", user)
          .applySetting("hibernate.connection.password", password)
          .applySetting("hibernate.default_schema", schema)
          .applySetting("hibernate.hbm2ddl.auto", "validate");
    }
    var registry = settings.applySettings(additional).build();
    try {
      var sources = new MetadataSources(registry);
      for (Class<?> type : entities) {
        sources.addAnnotatedClass(type);
      }
      factory = sources.buildMetadata().buildSessionFactory();
      if (url == null || url.isBlank()) {
        try (var session = factory.openSession()) {
          session.beginTransaction();
          session
              .createNativeMutationQuery(
                  "create table simpoint_resource_catalog_revision"
                      + " (id varchar(32) primary key, revision bigint not null)")
              .executeUpdate();
          session
              .createNativeMutationQuery(
                  "insert into simpoint_resource_catalog_revision values ('catalog',0)")
              .executeUpdate();
          session.getTransaction().commit();
        }
      }
      return factory;
    } catch (RuntimeException exception) {
      StandardServiceRegistryBuilder.destroy(registry);
      throw exception;
    }
  }

  private void install() throws Exception {
    var loader = getClass().getClassLoader();
    var manifest = new Properties();
    try (var input = loader.getResourceAsStream(ROOT + "manifest.properties")) {
      if (input == null) {
        throw new IllegalStateException("Identity migration manifest missing");
      }
      manifest.load(input);
    }
    try (var connection = DriverManager.getConnection(url, user, password);
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("create schema " + schema);
      statement.execute("set search_path to " + schema);
      for (String name : manifest.getProperty("migrations").split(",")) {
        fixture.before(connection, name.trim());
        try (var input = loader.getResourceAsStream(ROOT + "postgresql/" + name.trim())) {
          if (input == null) {
            throw new IllegalStateException("Identity migration missing: " + name);
          }
          statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
      connection.commit();
    }
  }

  @Override
  public void close() throws Exception {
    if (factory != null) {
      factory.close();
    }
    if (url != null && !url.isBlank()) {
      try (var connection = DriverManager.getConnection(url, user, password);
          var statement = connection.createStatement()) {
        statement.execute("drop schema if exists " + schema + " cascade");
      }
    }
  }
}
