package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationException;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Verifies packaged APP persistence without importing the runnable APP service. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class AppFormModuleMigrationPostgresTest {
  private final ModuleMigrationEngine migrations = new ModuleMigrationEngine();
  private final ModuleCatalog modules = ModuleCatalog.discover(getClass().getClassLoader());
  private MigrationPostgresDatabase database;

  @BeforeEach
  void setup() {
    database = new MigrationPostgresDatabase();
  }

  @AfterEach
  void cleanup() {
    if (database != null) {
      database.close();
    }
  }

  @Test
  void packagedSchemaValidatesAllFormAndCatalogueEntitiesAndRetainsDataOnRestart() {
    var selection = modules.plan(List.of("app-form"));
    var plan = plan(selection);
    assertEquals(11, plan.schemas().getFirst().tables().size());
    assertEquals(
        1, migrations.migrate(plan, Map.of("default", database.source())).getFirst().applied());
    seed(database.jdbc());
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(ModulePlan.class, () -> selection);
      context.registerBean("dataSource", javax.sql.DataSource.class, database::source);
      context.register(Persistence.class);
      context.refresh();
      var binding = context.getBean(ModuleStorageBindings.class).require("app-form");
      var manager = context.getBean(binding.entityManager(), EntityManager.class);
      assertEquals(14, manager.getMetamodel().getEntities().size());
      assertEquals(
          "客户资料",
          manager
              .createQuery("select a.name from WorkspaceApplicationReadModel a", String.class)
              .getSingleResult());
      assertEquals(
          "客户表单",
          manager
              .createQuery("select f.name from ApplicationFormEntity f", String.class)
              .getSingleResult());
      for (var entity : manager.getMetamodel().getEntities()) {
        manager
            .createQuery("select count(e) from " + entity.getName() + " e", Long.class)
            .getSingleResult();
      }
    }
    assertEquals(
        0, migrations.migrate(plan, Map.of("default", database.source())).getFirst().applied());
    migrations.verify(plan, Map.of("default", database.source()));
    assertEquals(
        1L, database.jdbc().queryForObject("select count(*) from simpoint_app_forms", Long.class));
  }

  @Test
  void newBaselineAndRepeatableLegacySchemaHaveTheSamePhysicalContract() {
    migrations.migrate(
        plan(modules.plan(List.of("app-form"))), Map.of("default", database.source()));
    try (var legacy = new MigrationPostgresDatabase()) {
      var initializer =
          new ResourceDatabasePopulator(
              new ClassPathResource("META-INF/simplepoint/modules/app-form/db/legacy.sql"));
      initializer.execute(legacy.source());
      initializer.execute(legacy.source());
      assertEquals(
          legacy.physicalContract("simpoint_app_%"), database.physicalContract("simpoint_app_%"));
      assertEquals(
          "LEGACY_SCHEMA_ADOPTION_REQUIRED",
          assertThrows(
                  ModuleMigrationException.class,
                  () ->
                      migrations.migrate(
                          plan(modules.plan(List.of("app-form"))),
                          Map.of("default", legacy.source())))
              .code());
    }
  }

  @Test
  void baselineEnforcesTenantOwnershipReleaseUniquenessAndPolicyProgress() {
    migrations.migrate(
        plan(modules.plan(List.of("app-form"))), Map.of("default", database.source()));
    var jdbc = database.jdbc();
    seed(jdbc);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                """
                insert into simpoint_app_form_drafts
                  (form_id, tenant_id, document_json, checksum, updated_at)
                values ('form', 'tenant-b', '{}', 'checksum', now())
                """));
    jdbc.update(
        """
        insert into simpoint_app_form_versions
          (id, tenant_id, form_id, version_number, document_json, checksum,
           storage_schema_json, storage_checksum, renderer_version, created_at)
        values ('version', 'tenant-a', 'form', 1, '{}', 'checksum', '{}', 'storage', '1', now())
        """);
    jdbc.update(
        """
        insert into simpoint_app_form_page_bindings
          (id, tenant_id, application_id, page_id, form_id, form_version, scene_id,
           idempotency_key, status, generation, resource_managed_key, created_at, updated_at)
        values ('binding', 'tenant-a', 'app', 'page', 'form', 1, 'main',
                'request', 'DATA_PREPARING', 1, 'managed-page', now(), now())
        """);
    jdbc.update(
        """
        insert into simpoint_app_form_release_keys
          (id, tenant_id, request_key, binding_id, created_at)
        values ('key', 'tenant-a', 'request', 'binding', now())
        """);
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("update simpoint_app_form_release_keys set tenant_id='tenant-b'"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                """
                insert into simpoint_app_form_release_keys
                  (id, tenant_id, request_key, binding_id, created_at)
                values ('duplicate', 'tenant-a', 'request', 'binding', now())
                """));
    jdbc.update(
        """
        insert into simpoint_app_form_policy_publications
          (id, tenant_id, application_id, form_id, page_id, binding_id, governing_version,
           expected_version, target_version, command_json, status, created_at, updated_at)
        values ('policy', 'tenant-a', 'app', 'form', 'page', 'binding', 1,
                0, 1, '{}', 'PREPARED', now(), now())
        """);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update("update simpoint_app_form_policy_publications set status='TARGET_READY'"));
    jdbc.update("update simpoint_app_form_policy_publications set status='CANCELLING'");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update("update simpoint_app_form_policy_publications set cancellation_json='{}'"));
    jdbc.update(
        """
        update simpoint_app_form_policy_publications set cancellation_json=?
        """,
        """
        {"cancelled":false,"version":0,"publicationFingerprint":"%s","targetFingerprint":null,
        "progress":{"contractVersion":1,"phase":"RESTORING","sequence":1,
                    "completedRecords":1,"totalRecords":2}}
        """
            .formatted("a".repeat(64)));
    assertEquals(
        "CANCELLING",
        jdbc.queryForObject(
            "select status from simpoint_app_form_policy_publications", String.class));
  }

  @Test
  void appAndWorkflowCanInstallInTheSameSchemaWithIndependentHistories() {
    var plan = plan(modules.plan(List.of("app-form", "workflow")));
    var outcomes = migrations.migrate(plan, Map.of("default", database.source()));
    assertEquals(4, outcomes.size());
    assertTrue(
        outcomes.stream()
            .allMatch(
                outcome -> outcome.applied() == (outcome.module().equals("workflow") ? 3 : 1)));
    seed(database.jdbc());
    assertTrue(
        migrations.migrate(plan, Map.of("default", database.source())).stream()
            .allMatch(outcome -> outcome.applied() == 0));
    migrations.verify(plan, Map.of("default", database.source()));
  }

  private static void seed(JdbcTemplate jdbc) {
    jdbc.update(
        """
        insert into simpoint_app_definitions
          (id, tenant_id, name, status, main_page_id, created_at, updated_at)
        values ('app', 'tenant-a', '客户资料', 'DRAFT', 'page', now(), now())
        """);
    jdbc.update(
        """
        insert into simpoint_app_pages
          (id, tenant_id, application_id, kind, name, sort_order, status, created_at, updated_at)
        values ('page', 'tenant-a', 'app', 'MAIN', '客户清单', 0, 'DRAFT', now(), now())
        """);
    jdbc.update(
        """
        insert into simpoint_app_forms
          (id, tenant_id, application_id, code, name, status, created_at, updated_at)
        values ('form', 'tenant-a', 'app', 'customer', '客户表单', 'DRAFT', now(), now())
        """);
  }

  private ModuleMigrationPlan plan(ModulePlan selection) {
    var binding = new ModuleStorageBindings.Binding("default", "source", "emf", "em", "tx", "jdbc");
    var bindings = new java.util.LinkedHashMap<String, ModuleStorageBindings.Binding>();
    selection.modules().forEach(module -> bindings.put(module.id(), binding));
    return ModuleMigrationPlan.discover(selection, new ModuleStorageBindings(bindings));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
