package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
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
import org.simplepoint.plugin.rbac.core.base.repository.JpaRoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceCatalogWriteBoundary;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Checks domain installation, security boundaries and preserved history on real PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class IdentityModuleMigrationPostgresTest {
  private static final List<String> MODULES =
      List.of("identity", "oidc", "i18n", "bootstrap-registry");
  private final ModuleMigrationEngine migrations = new ModuleMigrationEngine();
  private final ModuleCatalog catalog = ModuleCatalog.discover(getClass().getClassLoader());

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void installsAllMappingsAndPreservesRevocationsInSharedOrSeparateStores(boolean split) {
    try (var identity = new MigrationPostgresDatabase();
        var oidc = new MigrationPostgresDatabase();
        var i18n = new MigrationPostgresDatabase();
        var bootstrap = new MigrationPostgresDatabase();
        var context = new AnnotationConfigApplicationContext()) {
      var databases =
          Map.of("identity", identity, "oidc", oidc, "i18n", i18n, "bootstrap-registry", bootstrap);
      var assignments = new LinkedHashMap<String, Object>();
      Map<String, DataSource> sources = new LinkedHashMap<>();
      for (String module : MODULES) {
        if (split) {
          assignments.put("simplepoint.persistence.modules." + module, module);
          assignments.put("simplepoint.persistence.sources." + module, module + "Source");
          sources.put(module, databases.get(module).source());
          context.registerBean(module + "Source", DataSource.class, databases.get(module)::source);
        }
      }
      if (!split) {
        sources.put("default", identity.source());
        context.registerBean("dataSource", DataSource.class, identity::source);
      }
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("assignments", assignments));
      var modules = catalog.plan(MODULES);
      var persistence =
          ModulePersistencePlan.discover(
              modules, context.getEnvironment(), new DefaultResourceLoader());
      var plan = ModuleMigrationPlan.discover(modules, persistence.bindings());
      assertEquals(List.of(), plan.undeclaredModules());
      assertEquals(58, plan.schemas().stream().mapToInt(schema -> schema.tables().size()).sum());
      assertEquals(5, migrations.migrate(plan, sources).size());
      context.registerBean(ModulePlan.class, () -> modules);
      context.register(
          Persistence.class,
          ResourceCatalogWriteBoundary.class,
          org.simplepoint.plugin.rbac.resource.service.properties.ResourceRegistrationProperties
              .class,
          org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationCallerPolicy
              .class,
          org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationDeployments
              .class);
      context.refresh();
      int entities = 0;
      for (var store : persistence.stores().values()) {
        var em = context.getBean(store.binding().entityManager(), EntityManager.class);
        entities += em.getMetamodel().getEntities().size();
        for (var entity : em.getMetamodel().getEntities()) {
          long expected = entity.getName().equals("PlatformSecurityState") ? 1L : 0L;
          assertEquals(
              expected,
              em.createQuery("select count(e) from " + entity.getName() + " e", Long.class)
                  .getSingleResult());
        }
      }
      assertEquals(41, entities);
      var binding = persistence.bindings().require("identity");
      final var em = context.getBean(binding.entityManager(), EntityManager.class);
      var tx =
          new TransactionTemplate(
              context.getBean(binding.transactionManager(), PlatformTransactionManager.class));
      final var grants = context.getBean(JpaRoleResourceGrantRepository.class);
      tx.executeWithoutResult(
          status -> context.getBean(ResourceCatalogWriteBoundary.class).enter());
      assertEquals(
          1L,
          identity
              .jdbc()
              .queryForObject(
                  "select revision from simpoint_resource_catalog_revision where id='catalog'",
                  Long.class));
      var authority =
          context.getBean(
              org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationDeployments
                  .class);
      var operation = java.util.UUID.randomUUID();
      authority.apply(
          List.of(
              new org.simplepoint.plugin.rbac.resource.api.deployment.ResourceDeploymentChange(
                  "fixture",
                  0,
                  operation,
                  "fixture-deployment-" + operation.toString().replace("-", ""))));
      assertEquals(
          1L,
          identity
              .jdbc()
              .queryForObject(
                  "select generation from simpoint_resource_registration_deployments where"
                      + " owner='fixture'",
                  Long.class));
      String revokedId =
          tx.execute(
              status -> {
                var revoked = grant("old-resource");
                revoked.setDeletedAt(Instant.now());
                em.persist(revoked);
                em.persist(grant("active-resource"));
                em.flush();
                return revoked.getId();
              });
      assertNotNull(revokedId);
      assertEquals(
          List.of("active-resource"),
          grants.findByRoleIdIn(List.of("role")).stream()
              .map(RoleResourceGrant::getResourceCode)
              .toList());
      tx.executeWithoutResult(
          status -> grants.updateScopeForRole("tenant-a", "role", "scope", null));
      assertNull(em.find(RoleResourceGrant.class, revokedId).getDataScopeId());
      assertTrue(
          migrations.migrate(plan, sources).stream().allMatch(result -> result.applied() == 0));
      migrations.verify(plan, sources);
      assertEquals(
          List.of("active-resource"),
          grants.findByRoleIdIn(List.of("role")).stream()
              .map(RoleResourceGrant::getResourceCode)
              .toList());
      verifyLocaleAndRegistrationStorage(
          split ? i18n.jdbc() : identity.jdbc(), split ? bootstrap.jdbc() : identity.jdbc());
      var tokenStore = split ? oidc.jdbc() : identity.jdbc();
      String token = "test-token-".repeat(500);
      tokenStore.update(
          """
          insert into simpoint_ac_oauth2_authorization
            (id,registered_client_id,principal_name,authorization_grant_type,access_token_value)
          values ('authorization','client','test-actor','authorization_code',?)
          """,
          token);
      var authorization =
          context.getBean(
              persistence.bindings().require("oidc").entityManager(), EntityManager.class);
      assertEquals(
          token,
          authorization
              .createQuery("select a.accessTokenValue from Authorization a", String.class)
              .getSingleResult());
      assertTrue(
          migrations.migrate(plan, sources).stream().allMatch(result -> result.applied() == 0));
      assertEquals(
          "APPLIED",
          (split ? bootstrap.jdbc() : identity.jdbc())
              .queryForObject("select status from simpoint_platform_contribution", String.class));
      assertEquals(
          2L,
          (split ? i18n.jdbc() : identity.jdbc())
              .queryForObject("select count(*) from simpoint_i18n_messages", Long.class));
      if (split) {
        for (String module : MODULES) {
          var actual =
              databases
                  .get(module)
                  .jdbc()
                  .queryForList(
                      "select tablename from pg_tables where schemaname=current_schema()"
                          + " and tablename not like 'sp_migration_%'",
                      String.class);
          var owned =
              plan.schemas().stream()
                  .filter(
                      schema ->
                          schema.store().equals(persistence.bindings().require(module).store()))
                  .flatMap(schema -> schema.tables().stream())
                  .toList();
          assertEquals(new java.util.TreeSet<>(owned), new java.util.TreeSet<>(actual));
        }
      }
    }
  }

  @org.junit.jupiter.api.Test
  void bootstrapTransferUpgradePreservesAllLegacyStatesAndHistory() {
    try (var database = new MigrationPostgresDatabase()) {
      var latest = plan("bootstrap-registry");
      var schema = latest.schemas().getFirst();
      var previous =
          new ModuleMigrationPlan(
              List.of(
                  new ModuleMigrationPlan.ModuleSchema(
                      schema.module(),
                      schema.store(),
                      schema.historyTable(),
                      List.of("simpoint_platform_contribution"),
                      Map.of("postgresql", schema.scripts().get("postgresql").subList(0, 1)))));
      migrations.migrate(previous, Map.of("default", database.source()));
      for (String status : List.of("APPLIED", "RUNNING", "FAILED")) {
        database
            .jdbc()
            .update(
                """
                insert into simpoint_platform_contribution
                  (service_name,module_code,contribution_type,contribution_key,
                   version,checksum,status,error,applied_at)
                values ('common','fixture','data',?,'1','checksum',?,'retained diagnostic',now())
                """,
                status,
                status);
      }
      var records =
          database
              .jdbc()
              .queryForList(
                  "select * from simpoint_platform_contribution order by contribution_key");
      assertEquals(
          1, migrations.migrate(latest, Map.of("default", database.source())).getFirst().applied());
      assertEquals(
          records,
          database
              .jdbc()
              .queryForList(
                  "select * from simpoint_platform_contribution order by contribution_key"));
      assertEquals(
          0L,
          database
              .jdbc()
              .queryForObject(
                  "select revision from simpoint_bootstrap_registry_revision", Long.class));
      assertEquals(
          0L,
          database
              .jdbc()
              .queryForObject(
                  "select count(*) from simpoint_bootstrap_legacy_transfers", Long.class));
      assertEquals(
          0, migrations.migrate(latest, Map.of("default", database.source())).getFirst().applied());
      migrations.verify(latest, Map.of("default", database.source()));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 4, 5, 6, 7})
  void catalogRevisionUpgradePreservesResourcesAndRevokedGrants(int previousVersion) {
    try (var database = new MigrationPostgresDatabase()) {
      var latest = plan("identity");
      var identity =
          latest.schemas().stream()
              .filter(schema -> schema.module().equals("identity"))
              .findFirst()
              .orElseThrow();
      var previous =
          new ModuleMigrationPlan(
              List.of(
                  new ModuleMigrationPlan.ModuleSchema(
                      identity.module(),
                      identity.store(),
                      identity.historyTable(),
                      identity.tables().stream()
                          .filter(
                              table ->
                                  !table.equals("simpoint_dictionary_legacy_adoptions")
                                      && (previousVersion >= 7
                                          || !table.startsWith("simpoint_dictionary_registration_"))
                                      && (previousVersion >= 6
                                          || !table.equals("simpoint_dictionary_catalog_revision"))
                                      && (previousVersion >= 5
                                          || !table.startsWith("simpoint_resource_registration_"))
                                      && (previousVersion >= 4
                                          || !table.equals("simpoint_resource_catalog_revision")))
                          .toList(),
                      Map.of(
                          "postgresql",
                          identity.scripts().get("postgresql").subList(0, previousVersion)))));
      migrations.migrate(previous, Map.of("default", database.source()));
      var jdbc = database.jdbc();
      jdbc.update(
          """
          insert into simpoint_ac_resources
            (id,code,name,type,plugin_id,path,disabled,grantable,public_access,require_org_tenant)
          values ('stable','page','页面','PAGE','owner','/page',false,true,false,false)
          """);
      jdbc.update(
          """
          insert into simpoint_ac_role_resource_grants
            (id,tenant_id,role_id,resource_code,deleted_at)
          values ('revoked','tenant','role','page',now())
          """);
      if (previousVersion >= 4) {
        jdbc.update("update simpoint_resource_catalog_revision set revision=7");
      }
      jdbc.update(
          "insert into simpoint_saas_dictionaries(id,code,name,enabled) values"
              + " ('dict','legacy','历史字典',false)");
      jdbc.update(
          "insert into simpoint_saas_dictionary_items(id,dictionary_code,item_value,name,enabled)"
              + " values ('item','legacy','KEEP','历史选项',false)");
      var dictionaries = jdbc.queryForList("select * from simpoint_saas_dictionaries");
      var items = jdbc.queryForList("select * from simpoint_saas_dictionary_items");
      final var resources = jdbc.queryForList("select * from simpoint_ac_resources");
      final var grants = jdbc.queryForList("select * from simpoint_ac_role_resource_grants");
      var changes = migrations.migrate(latest, Map.of("default", database.source()));
      assertEquals(
          8 - previousVersion,
          changes.stream()
              .filter(change -> change.module().equals("identity"))
              .findFirst()
              .orElseThrow()
              .applied());
      assertEquals(dictionaries, jdbc.queryForList("select * from simpoint_saas_dictionaries"));
      assertEquals(items, jdbc.queryForList("select * from simpoint_saas_dictionary_items"));
      assertEquals(
          0L,
          jdbc.queryForObject(
              "select revision from simpoint_dictionary_catalog_revision where id='catalog'",
              Long.class));
      assertEquals(resources, jdbc.queryForList("select * from simpoint_ac_resources"));
      assertEquals(grants, jdbc.queryForList("select * from simpoint_ac_role_resource_grants"));
      assertEquals(
          previousVersion >= 4 ? 7L : 0L,
          jdbc.queryForObject(
              "select revision from simpoint_resource_catalog_revision", Long.class));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("insert into simpoint_resource_catalog_revision values ('foreign',0)"));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("update simpoint_resource_catalog_revision set revision=-1"));
      assertEquals(
          0L,
          jdbc.queryForObject(
              "select count(*) from simpoint_resource_registration_deployments", Long.class));
      final String seededOperation = java.util.UUID.randomUUID().toString();
      jdbc.update(
          "insert into simpoint_resource_registration_operations (operation_id,fingerprint) values"
              + " (?,?)",
          seededOperation,
          "0".repeat(64));
      jdbc.update(
          "insert into simpoint_resource_registration_deployments (owner,generation,operation_id)"
              + " values ('owner',1,?)",
          seededOperation);
      assertThrows(
          DataIntegrityViolationException.class,
          () ->
              jdbc.update(
                  "insert into simpoint_resource_registration_deployments"
                      + " (owner,generation,operation_id) values ('foreign',0,?)",
                  seededOperation));
      jdbc.update("update simpoint_resource_catalog_revision set revision=7");
      assertTrue(
          migrations.migrate(latest, Map.of("default", database.source())).stream()
              .allMatch(change -> change.applied() == 0));
      assertEquals(
          7L,
          jdbc.queryForObject(
              "select revision from simpoint_resource_catalog_revision", Long.class));
      assertEquals(
          1L,
          jdbc.queryForObject(
              "select count(*) from simpoint_resource_registration_deployments", Long.class));
    }
  }

  @Test
  void identityBaselineRetainsProductionGuardsDefaultsAndAuthenticationColumns() {
    try (var database = new MigrationPostgresDatabase()) {
      var plan = plan("identity");
      migrations.migrate(plan, Map.of("default", database.source()));
      var jdbc = database.jdbc();
      assertEquals(0L, jdbc.queryForObject("select count(*) from simpoint_ac_users", Long.class));
      assertEquals(
          0L,
          jdbc.queryForObject("select count(*) from simpoint_platform_identity_role", Long.class));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("insert into simpoint_platform_identity (user_id) values ('missing')"));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("insert into simpoint_platform_step_up (user_id) values ('missing')"));
      jdbc.update(
          "insert into simpoint_ac_users (id, two_factor_secret, two_factor_pending_until)"
              + " values ('user', ?, now())",
          "x".repeat(512));
      assertEquals(
          0L,
          jdbc.queryForObject(
              "select authorization_version from simpoint_ac_users where id='user'", Long.class));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("update simpoint_ac_users set two_factor_secret=?", "x".repeat(513)));
      jdbc.update("insert into simpoint_platform_identity (user_id) values ('user')");
      assertThrows(
          DataIntegrityViolationException.class,
          () ->
              jdbc.update(
                  "insert into simpoint_platform_identity_role (user_id,role_code) values"
                      + " ('user','UNKNOWN')"));
      jdbc.update("insert into simpoint_platform_step_up (user_id) values ('user')");
      assertEquals(
          0, jdbc.queryForObject("select failures from simpoint_platform_step_up", Integer.class));
      jdbc.update(
          "insert into simpoint_saas_tenant_user_rel (id,tenant_id,user_id)"
              + " values ('member','tenant-a','user')");
      assertEquals(
          "INTERNAL",
          jdbc.queryForObject("select user_type from simpoint_saas_tenant_user_rel", String.class));
      assertThrows(
          DataIntegrityViolationException.class,
          () ->
              jdbc.update(
                  "insert into simpoint_saas_tenant_user_rel (id,tenant_id,user_id)"
                      + " values ('duplicate','tenant-a','user')"));
      jdbc.update(
          """
          insert into simpoint_saas_tenant_resources
            (tenant_id,id,code,name,type,managed_owner,managed_key)
          values ('tenant-a','page','page','客户资料','PAGE','app','page-key')
          """);
      assertThrows(
          DataIntegrityViolationException.class,
          () ->
              jdbc.update(
                  "update simpoint_saas_tenant_resources set platform_resource_id='foreign'"));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("update simpoint_saas_tenant_resources set managed_key=null"));
      jdbc.update(
          "update simpoint_saas_tenant_resources set formally_offline=true,lifecycle_generation=7");
      migrations.migrate(plan, Map.of("default", database.source()));
      assertEquals(
          Boolean.TRUE,
          jdbc.queryForObject(
              "select formally_offline from simpoint_saas_tenant_resources", Boolean.class));
      assertEquals(
          7L,
          jdbc.queryForObject(
              "select lifecycle_generation from simpoint_saas_tenant_resources", Long.class));
      var indexes =
          jdbc.queryForList(
              "select indexname from pg_indexes where schemaname=current_schema()", String.class);
      assertTrue(
          indexes.containsAll(
              List.of(
                  "idx_tenant_resources_parent",
                  "idx_tenant_resources_target",
                  "idx_tenant_resource_managed_parent",
                  "idx_data_scope_tenant_page",
                  "idx_tenant_member_org_page",
                  "idx_organization_tenant_parent",
                  "idx_resource_ancestor_children",
                  "ix_platform_audit_time",
                  "ix_platform_audit_target")));
    }
  }

  private ModuleMigrationPlan plan(String module) {
    var modules = catalog.plan(List.of(module));
    var persistence =
        ModulePersistencePlan.discover(
            modules, new StandardEnvironment(), new DefaultResourceLoader());
    return ModuleMigrationPlan.discover(modules, persistence.bindings());
  }

  private static RoleResourceGrant grant(String resource) {
    var grant = new RoleResourceGrant();
    grant.setTenantId("tenant-a");
    grant.setRoleId("role");
    grant.setResourceCode(resource);
    return grant;
  }

  private static void verifyLocaleAndRegistrationStorage(
      JdbcTemplate locale, JdbcTemplate bootstrap) {
    locale.update(
        """
        insert into simpoint_i18n_messages (id,global,locale,namespace,code,message)
        values ('zh',false,'zh-CN','module','label','客户资料')
        """);
    locale.update(
        """
        insert into simpoint_i18n_messages (id,global,locale,namespace,code,message)
        values ('ja',false,'ja-JP','module','label','顧客情報')
        """);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            locale.update(
                """
                insert into simpoint_i18n_messages (id,global,locale,namespace,code,message)
                values ('duplicate',false,'zh-CN','module','label','duplicate')
                """));
    bootstrap.update(
        """
        insert into simpoint_platform_contribution
          (service_name,module_code,contribution_type,contribution_key,status,checksum)
        values ('logical-owner','identity','RESOURCE','directory','APPLIED','checksum')
        """);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            bootstrap.update(
                """
                insert into simpoint_platform_contribution
                  (service_name,module_code,contribution_type,contribution_key,status,checksum)
                values ('logical-owner','identity','RESOURCE','directory','RUNNING','changed')
                """));
    assertEquals(
        "APPLIED",
        bootstrap.queryForObject(
            "select status from simpoint_platform_contribution", String.class));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
