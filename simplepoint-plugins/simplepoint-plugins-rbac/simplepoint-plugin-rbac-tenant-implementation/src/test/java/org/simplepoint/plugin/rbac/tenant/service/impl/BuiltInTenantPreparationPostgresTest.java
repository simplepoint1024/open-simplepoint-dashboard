package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.plugin.rbac.tenant.api.service.BuiltInTenantProvisioner;
import org.simplepoint.plugin.rbac.tenant.service.initialize.BuiltInTenantWorkspaceInitializer;
import org.simplepoint.plugin.rbac.tenant.service.properties.BuiltInTenantBootstrapProperties;
import org.simplepoint.security.entity.User;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Actual identity migrations, owned repositories and transaction proxies across bootstrap and registration. */
@EnabledIfEnvironmentVariable(named = "SP_TENANT_PACKAGE_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class BuiltInTenantPreparationPostgresTest {
  private final String schema = "tenant_bootstrap_" + UUID.randomUUID().toString().replace("-", "");
  private JdbcTemplate admin;
  private JdbcTemplate jdbc;
  private DataSource source;
  private ModulePlan modules;
  private ModulePersistencePlan persistence;
  private AnnotationConfigApplicationContext context;

  @BeforeEach
  void setup() {
    String url = System.getenv("SP_TENANT_PACKAGE_TEST_POSTGRES_URL");
    String user = System.getenv().getOrDefault("SP_TENANT_PACKAGE_TEST_POSTGRES_USER", "postgres");
    String password = System.getenv().getOrDefault("SP_TENANT_PACKAGE_TEST_POSTGRES_PASSWORD", "");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    admin.execute("create schema " + schema);
    source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
    jdbc = new JdbcTemplate(source);
    modules = ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("identity"));
    persistence = ModulePersistencePlan.discover(modules, new StandardEnvironment(), new DefaultResourceLoader());
    new ModuleMigrationEngine().migrate(ModuleMigrationPlan.discover(modules, persistence.bindings()), Map.of("default", source));
    for (String id : List.of("owner", "manager", "member")) {
      jdbc.update("insert into simpoint_ac_users (id,email,name,authorization_version) values (?,?,?,0)",
          id, id + "@example.test", id);
    }
    startContext();
  }

  private void startContext() {
    context = new AnnotationConfigApplicationContext();
    context.registerBean(ModulePlan.class, () -> modules);
    for (var store : persistence.stores().values()) {
      context.registerBean(store.binding().dataSource(), DataSource.class, () -> source);
    }
    var properties = new BuiltInTenantBootstrapProperties();
    properties.setOrganizationName("Bootstrap organization");
    properties.setOwnerSubject("owner@example.test");
    properties.setManagerSubject("manager@example.test");
    properties.setMemberSubject("member@example.test");
    var users = mock(UserManagementService.class);
    for (String id : List.of("owner", "manager", "member")) {
      var user = new User();
      user.setId(id);
      when(users.loadUserByPhoneOrEmail(id + "@example.test")).thenReturn(user);
    }
    context.registerBean(BuiltInTenantBootstrapProperties.class, () -> properties);
    context.registerBean(UserManagementService.class, () -> users);
    context.register(Persistence.class, ResourceAuthorizationVersionServiceImpl.class,
        TenantResourceProvisioningServiceImpl.class, BuiltInTenantResourcePreparation.class,
        BuiltInTenantProvisionerImpl.class, BuiltInTenantWorkspaceWriter.class);
    context.refresh();
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.resetRequestAttributes();
    if (context != null) {
      context.close();
    }
    if (admin != null) {
      admin.execute("drop schema if exists " + schema + " cascade");
    }
  }

  @Test
  void stagedOrganizationSurvivesRestartAndRegistersOnceWithoutImplicitPackageOrRoleGrants() {
    bootstrap();
    assertThat(state()).isEqualTo("INITIALIZATION_REQUIRED");
    assertThat(count("simpoint_saas_tenant_user_rel")).isEqualTo(3);
    assertThat(count("simpoint_ac_roles")).isEqualTo(3);
    assertThat(count("simpoint_saas_tenant_resources")).isZero();
    assertNoGrants();
    context.close();
    startContext();
    platformResource("base", "base.view");
    platformResource("storage", "storage.view");
    register("base.view", "storage.view");
    assertThat(state()).isEqualTo("READY");
    assertThat(jdbc.queryForList("select id from simpoint_saas_tenant_resources order by id", String.class))
        .containsExactly("base", "storage");
    assertThat(jdbc.queryForObject("select authorization_version from simpoint_saas_tenants", Long.class)).isEqualTo(1L);
    var ready = jdbc.queryForMap("select * from simpoint_saas_tenant_resource_setup");
    register("base.view", "storage.view");
    assertThat(jdbc.queryForMap("select * from simpoint_saas_tenant_resource_setup")).isEqualTo(ready);
    assertThat(jdbc.queryForObject("select authorization_version from simpoint_saas_tenants", Long.class)).isEqualTo(1L);
    // Registration updates the platform membership, not an already-ready tenant's chosen directory.
    jdbc.update("update simpoint_ac_resources set name='Changed platform name' where id='base'");
    platformResource("later", "later.view");
    register("base.view", "storage.view", "later.view");
    assertThat(count("simpoint_saas_tenant_resources")).isEqualTo(2);
    assertThat(jdbc.queryForObject("select name from simpoint_saas_tenant_resources where id='base'", String.class)).isEqualTo("base");
    assertNoGrants();
  }

  @Test
  void lateBootstrapFailureRollsBackTenantMarkerAndAllSeedRelationsThenRetrySucceeds() {
    jdbc.execute("alter table simpoint_ac_roles add constraint reject_member check (authority <> 'ROLE_TENANT_MEMBER')");
    assertThatThrownBy(this::bootstrap).isInstanceOf(RuntimeException.class);
    for (String table : List.of("simpoint_saas_tenants", "simpoint_saas_tenant_resource_setup",
        "simpoint_saas_applications", "simpoint_saas_packages", "simpoint_saas_tenant_user_rel", "simpoint_ac_roles")) {
      assertThat(count(table)).as(table).isZero();
    }
    assertThat(count("simpoint_ac_users")).isEqualTo(3);
    jdbc.execute("alter table simpoint_ac_roles drop constraint reject_member");
    bootstrap();
    assertThat(state()).isEqualTo("INITIALIZATION_REQUIRED");
    assertNoGrants();
  }

  @Test
  void failedRegistrationRollsBackMembershipAndDirectoryAndLeavesPendingForRetry() {
    bootstrap();
    platformResource("base", "base.view");
    jdbc.update("update simpoint_saas_applications set enabled=false where code='simplepoint-storage'");
    assertThatThrownBy(() -> register("base.view")).isInstanceOf(RuntimeException.class);
    assertThat(state()).isEqualTo("INITIALIZATION_REQUIRED");
    assertThat(count("simpoint_saas_application_resource_rel")).isZero();
    assertThat(count("simpoint_saas_tenant_resources")).isZero();
    assertThat(jdbc.queryForObject("select authorization_version from simpoint_saas_tenants", Long.class)).isZero();
    jdbc.update("update simpoint_saas_applications set enabled=true where code='simplepoint-storage'");
    register("base.view");
    assertThat(state()).isEqualTo("READY");
    assertThat(count("simpoint_saas_tenant_resources")).isEqualTo(1);
    assertNoGrants();
  }

  @Test
  void historicalTenantIsNeverAdoptedByOrdinaryRegistration() {
    bootstrap();
    jdbc.update("update simpoint_saas_tenant_resource_setup set state='ADOPTION_REQUIRED'");
    platformResource("base", "base.view");
    register("base.view");
    assertThat(state()).isEqualTo("ADOPTION_REQUIRED");
    assertThat(count("simpoint_saas_tenant_resources")).isZero();
    assertNoGrants();
  }

  @Test
  void emptyAuthoritativeDeclarationFailsWhenPendingTenantHasNoBaseline() {
    bootstrap();
    assertThatThrownBy(this::register).isInstanceOf(RuntimeException.class);
    assertThat(state()).isEqualTo("INITIALIZATION_REQUIRED");
    assertThat(count("simpoint_saas_tenant_resources")).isZero();
    assertNoGrants();
  }

  @Test
  void failureAfterDirectoryWritesRollsBackTheWholeRegistrationHandoff() {
    bootstrap();
    platformResource("base", "base.view");
    jdbc.execute("alter table simpoint_saas_tenant_resource_setup add constraint reject_ready check (state <> 'READY')");
    assertThatThrownBy(() -> register("base.view")).isInstanceOf(RuntimeException.class);
    assertThat(state()).isEqualTo("INITIALIZATION_REQUIRED");
    assertThat(count("simpoint_saas_tenant_resources")).isZero();
    assertThat(count("simpoint_saas_application_resource_rel")).isZero();
    assertThat(jdbc.queryForObject("select authorization_version from simpoint_saas_tenants", Long.class)).isZero();
    jdbc.execute("alter table simpoint_saas_tenant_resource_setup drop constraint reject_ready");
    register("base.view");
    assertThat(state()).isEqualTo("READY");
    assertThat(count("simpoint_saas_tenant_resources")).isEqualTo(1);
    assertNoGrants();
  }

  @Test
  void concurrentPendingCompletionInitializesOnlyOnce() throws Exception {
    bootstrap();
    platformResource("base", "base.view");
    jdbc.update("insert into simpoint_saas_application_resource_rel (id,application_code,resource_code) "
        + "values ('base-member','simplepoint-core','base.view')");
    var transaction = new TransactionTemplate(context.getBean(persistence.bindings().require("identity").transactionManager(),
        PlatformTransactionManager.class));
    var ready = new CountDownLatch(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var tasks = List.of(1, 2).stream().map(ignored -> workers.submit(() -> {
        ready.countDown();
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        transaction.executeWithoutResult(status -> context.getBean(BuiltInTenantResourcePreparation.class).completePending());
        return true;
      })).toList();
      for (var task : tasks) {
        assertThat(task.get(20, TimeUnit.SECONDS)).isTrue();
      }
    }
    assertThat(state()).isEqualTo("READY");
    assertThat(count("simpoint_saas_tenant_resources")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select authorization_version from simpoint_saas_tenants", Long.class)).isEqualTo(1L);
    assertNoGrants();
  }

  @Test
  void bootstrapDoesNotResetExistingApplicationPackageOrTenantConfiguration() {
    bootstrap();
    jdbc.update("update simpoint_saas_applications set name='Configured app',enabled=false where code='simplepoint-core'");
    jdbc.update("update simpoint_saas_packages set name='Configured package',enabled=false where code='simplepoint-standard'");
    jdbc.update("update simpoint_saas_tenants set description='Configured organization'");
    bootstrap();
    assertThat(count("simpoint_saas_tenants")).isEqualTo(1);
    assertThat(count("simpoint_saas_tenant_user_rel")).isEqualTo(3);
    assertThat(jdbc.queryForObject("select description from simpoint_saas_tenants", String.class)).isEqualTo("Configured organization");
    assertThat(jdbc.queryForMap("select name,enabled from simpoint_saas_applications where code='simplepoint-core'"))
        .containsEntry("name", "Configured app").containsEntry("enabled", false);
    assertThat(jdbc.queryForMap("select name,enabled from simpoint_saas_packages where code='simplepoint-standard'"))
        .containsEntry("name", "Configured package").containsEntry("enabled", false);
    assertThat(state()).isEqualTo("INITIALIZATION_REQUIRED");
    assertNoGrants();
  }

  @Test
  void bootstrapDeclarationRetainsIdentityAndDoesNotWriteUntilItsActionRuns() throws Exception {
    var contribution = new BuiltInTenantWorkspaceInitializer(context.getBean(BuiltInTenantWorkspaceWriter.class))
        .builtInTenantWorkspaceBootstrapContribution().contribution();
    assertThat(contribution.moduleCode()).isEqualTo("rbac-tenant");
    assertThat(contribution.contributionType()).isEqualTo("workspace");
    assertThat(contribution.contributionKey()).isEqualTo("default-tenant-workspace");
    assertThat(contribution.version()).isEqualTo("3");
    assertThat(contribution.order()).isEqualTo(400);
    assertThat(count("simpoint_saas_tenants")).isZero();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    assertThatThrownBy(() -> contribution.action().run()).isInstanceOf(AccessDeniedException.class);
    assertThat(count("simpoint_saas_tenants")).isZero();
    RequestContextHolder.resetRequestAttributes();
    contribution.action().run();
    assertThat(state()).isEqualTo("INITIALIZATION_REQUIRED");
  }

  private void bootstrap() {
    context.getBean(BuiltInTenantWorkspaceWriter.class).initializeWorkspace();
  }

  private void register(String... codes) {
    context.getBean(BuiltInTenantProvisioner.class).provisionApplicationResources("common", List.of(codes), List.of(codes));
  }

  private void platformResource(String id, String code) {
    jdbc.update("insert into simpoint_ac_resources (id,code,name,type,scope_types,disabled,grantable,public_access,require_org_tenant) "
        + "values (?,?,?,'PAGE','TENANT,PERSONAL',false,true,false,false)", id, code, id);
  }

  private String state() {
    return jdbc.queryForObject("select state from simpoint_saas_tenant_resource_setup", String.class);
  }

  private void assertNoGrants() {
    for (String table : List.of("simpoint_saas_tenant_package_rel", "simpoint_saas_tenant_resource_credits",
        "simpoint_saas_tenant_package_instances", "simpoint_ac_role_resource_grants")) {
      assertThat(count(table)).as(table).isZero();
    }
  }

  private long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
