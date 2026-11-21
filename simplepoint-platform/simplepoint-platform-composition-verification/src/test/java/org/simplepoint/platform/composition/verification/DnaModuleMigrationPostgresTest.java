package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses real packaged DNA domains and PostgreSQL schemas, with no runnable service dependencies. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class DnaModuleMigrationPostgresTest {
  private final ModuleMigrationEngine migrations = new ModuleMigrationEngine();
  private final ModulePlan modules =
      ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("dna-runtime"));

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void migratesAndValidatesAllDnaEntitiesInSharedOrSplitStorage(boolean split) {
    try (var primary = new MigrationPostgresDatabase();
        var runtime = new MigrationPostgresDatabase();
        var context = new AnnotationConfigApplicationContext()) {
      if (split) {
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(
                new MapPropertySource(
                    "split",
                    Map.of(
                        "simplepoint.persistence.modules.dna-runtime", "runtime",
                        "simplepoint.persistence.sources.runtime", "runtimeSource")));
      }
      var persistence =
          ModulePersistencePlan.discover(
              modules, context.getEnvironment(), new DefaultResourceLoader());
      var plan = ModuleMigrationPlan.discover(modules, persistence.bindings());
      assertEquals(List.of(), plan.undeclaredModules());
      assertEquals(
          List.of("dna-core", "dna-federation", "dna-runtime", "platform-events"),
          plan.schemas().stream().map(ModuleMigrationPlan.ModuleSchema::module).toList());
      assertEquals(30, plan.schemas().stream().mapToInt(schema -> schema.tables().size()).sum());
      Map<String, javax.sql.DataSource> sources =
          split
              ? Map.of("default", primary.source(), "runtime", runtime.source())
              : Map.of("default", primary.source());
      assertTrue(
          migrations.migrate(plan, sources).stream()
              .allMatch(
                  result -> result.applied() == (result.module().equals("dna-runtime") ? 6 : 1)));
      context.registerBean(ModulePlan.class, () -> modules);
      context.registerBean("dataSource", javax.sql.DataSource.class, primary::source);
      if (split) {
        context.registerBean("runtimeSource", javax.sql.DataSource.class, runtime::source);
      }
      context.register(Persistence.class);
      context.refresh();
      int entityCount = 0;
      for (var store : persistence.stores().values()) {
        var manager = context.getBean(store.binding().entityManager(), EntityManager.class);
        entityCount += manager.getMetamodel().getEntities().size();
        for (var entity : manager.getMetamodel().getEntities()) {
          assertEquals(
              0L,
              manager
                  .createQuery("select count(e) from " + entity.getName() + " e", Long.class)
                  .getSingleResult());
        }
      }
      assertEquals(25, entityCount);
      primary
          .jdbc()
          .update(
              """
              insert into simpoint_dna_federation_jdbc_users
                (id, catalog_id, catalog_code, catalog_name, user_id, user_display_name,
                 operation_permissions)
              values ('jdbc-user', 'catalog', 'catalog-code', '查询目录', 'actor', '测试用户',
                      ARRAY['METADATA', 'QUERY'])
              """);
      var federation =
          context.getBean(
              persistence.bindings().require("dna-federation").entityManager(),
              EntityManager.class);
      assertEquals(
          java.util.Set.of("METADATA", "QUERY"),
          federation
              .createQuery(
                  "select u.operationPermissions from FederationJdbcConnectionUser u", Object.class)
              .getSingleResult());

      assertEquals(
          persistence.bindings().require("dna-core"),
          persistence.bindings().require("dna-federation"));
      var runtimeDatabase = split ? runtime : primary;
      seedModel(runtimeDatabase.jdbc());
      assertTrue(
          migrations.migrate(plan, sources).stream().allMatch(result -> result.applied() == 0));
      migrations.verify(plan, sources);
      assertEquals(
          1L,
          runtimeDatabase
              .jdbc()
              .queryForObject("select count(*) from simpoint_dna_physical_models", Long.class));
      if (split) {
        assertEquals(
            0,
            primary
                .jdbc()
                .queryForObject(
                    "select count(*) from information_schema.tables where"
                        + " table_schema=current_schema() and"
                        + " table_name='simpoint_dna_physical_models'",
                    Integer.class));
        assertEquals(
            0,
            runtime
                .jdbc()
                .queryForObject(
                    "select count(*) from information_schema.tables where"
                        + " table_schema=current_schema() and"
                        + " table_name='simpoint_dna_jdbc_data_sources'",
                    Integer.class));
      }
    }
  }

  @Test
  void runtimeBaselineRetainsTheDeployedConstraintsAndIndexes() throws java.io.IOException {
    try (var current = new MigrationPostgresDatabase();
        var legacy = new MigrationPostgresDatabase()) {
      var plan = plan();
      // Compare the immutable V1 baseline, not later additive module migrations, to legacy DDL.
      try (var baseline =
          getClass()
              .getClassLoader()
              .getResourceAsStream(
                  "META-INF/simplepoint/modules/dna-runtime/db/postgresql/V1__dna_runtime_foundation.sql")) {
        current
            .jdbc()
            .execute(
                new String(
                    java.util.Objects.requireNonNull(baseline).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
      }
      Path directory = Path.of(System.getProperty("simplepoint.dna.legacy-sql"));
      // JDBC lets PostgreSQL parse the historical DO block and transaction delimiters.
      for (int replay = 0; replay < 2; replay++) {
        for (String name :
            List.of(
                "20260929-dna-form-runtime-foundation.sql",
                "20260929-dna-form-runtime-publication.sql",
                "20260930-dna-record-runtime.sql",
                "20261013-dna-record-create-idempotency.sql",
                "20261013-dna-resource-projections.sql",
                "20261015-dna-resource-lifecycle.sql")) {
          legacy.jdbc().execute(read(directory.resolve(name)));
        }
      }
      var tables =
          plan.schemas().stream()
              .filter(schema -> schema.module().equals("dna-runtime"))
              .findFirst()
              .orElseThrow()
              .tables();
      assertEquals(
          legacy.physicalContract("simpoint_dna_%"),
          current.physicalContract("simpoint_dna_%").stream()
              .filter(row -> tables.contains(row.get("owner")))
              .toList());
    }
  }

  @Test
  void runtimeConstraintsFenceTenantReceiptsAndResourceGenerations() {
    try (var database = new MigrationPostgresDatabase()) {
      migrations.migrate(plan(), Map.of("default", database.source()));
      var jdbc = database.jdbc();
      seedModel(jdbc);
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("update simpoint_dna_physical_models set active_version=2"));
      jdbc.update(
          """
          insert into simpoint_dna_record_create_receipts
            (id, tenant_id, physical_model_id, idempotency_key, request_fingerprint,
             record_id, status, created_at, updated_at)
          values ('receipt', 'tenant-a', 'model', 'request', 'fingerprint',
                  'record', 'PENDING', now(), now())
          """);
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("update simpoint_dna_record_create_receipts set tenant_id='tenant-b'"));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("update simpoint_dna_record_create_receipts set status='SUCCEEDED'"));
      jdbc.update(
          """
          update simpoint_dna_record_create_receipts
             set status='SUCCEEDED', completed_at=now(), result_updated_at=now()
          """);
      jdbc.update(
          """
          insert into simpoint_dna_resource_projections
            (id, tenant_id, resource_code, created_by, updated_by, created_at, updated_at)
          values ('projection', 'tenant-a', 'customers', 'actor', 'actor', now(), now())
          """);
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("update simpoint_dna_resource_projections set generation=1"));
      jdbc.update(
          """
update simpoint_dna_resource_projections
   set owner_key='app-page', generation=1, physical_model_id='model', formally_offline=true
""");
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("update simpoint_dna_resource_projections set tenant_id='tenant-b'"));
      migrations.verify(plan(), Map.of("default", database.source()));
      assertEquals(
          Boolean.TRUE,
          jdbc.queryForObject(
              "select formally_offline from simpoint_dna_resource_projections", Boolean.class));
    }
  }

  private ModuleMigrationPlan plan() {
    var persistence =
        ModulePersistencePlan.discover(
            modules,
            new org.springframework.core.env.StandardEnvironment(),
            new DefaultResourceLoader());
    return ModuleMigrationPlan.discover(modules, persistence.bindings());
  }

  private static String read(Path file) {
    try {
      return java.nio.file.Files.readString(file);
    } catch (java.io.IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }

  private static void seedModel(JdbcTemplate jdbc) {
    jdbc.update(
        """
insert into simpoint_dna_physical_models
  (id, tenant_id, app_id, form_id, display_name, status, latest_version, created_at, updated_at)
values ('model', 'tenant-a', 'app', 'form', '客户模型', 'DRAFT', 1, now(), now())
""");
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
