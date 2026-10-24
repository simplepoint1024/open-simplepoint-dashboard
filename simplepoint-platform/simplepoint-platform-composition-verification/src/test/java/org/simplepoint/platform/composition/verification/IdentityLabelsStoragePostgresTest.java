package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityRequest;
import org.simplepoint.plugin.rbac.tenant.service.impl.AuditIdentityDirectoryImpl;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

/** Actual identity-owned provider in shared/split storage; workflow list joins remain G22 work. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class IdentityLabelsStoragePostgresTest {
  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void providerUsesIdentityStorageWithCurrentTenantMembershipAndRoleNames(boolean split) {
    try (var identity = new MigrationPostgresDatabase();
        var workflow = new MigrationPostgresDatabase();
        var context = new AnnotationConfigApplicationContext()) {
      var modules =
          ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("identity", "workflow"));
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "stores",
                  split
                      ? Map.of(
                          "simplepoint.persistence.modules.identity",
                          "identity",
                          "simplepoint.persistence.sources.identity",
                          "identitySource",
                          "simplepoint.persistence.modules.workflow",
                          "workflow",
                          "simplepoint.persistence.sources.workflow",
                          "workflowSource")
                      : Map.of()));
      var persistence =
          ModulePersistencePlan.discover(
              modules, context.getEnvironment(), new DefaultResourceLoader());
      new ModuleMigrationEngine()
          .migrate(
              ModuleMigrationPlan.discover(modules, persistence.bindings()),
              split
                  ? Map.of("identity", identity.source(), "workflow", workflow.source())
                  : Map.of("default", identity.source()));
      context.registerBean(
          split ? "identitySource" : "dataSource", DataSource.class, identity::source);
      if (split) {
        context.registerBean(
            "workflowSource",
            DataSource.class,
            workflow::source,
            definition -> definition.setPrimary(true));
      }
      context.registerBean(ModulePlan.class, () -> modules);
      context.register(Persistence.class, AuditIdentityDirectoryImpl.class);
      context.refresh();
      identity
          .jdbc()
          .execute(
              """
              insert into simpoint_ac_users (id,name,email,enabled) values
                ('owner','空间负责人','owner@example.test',true),
                ('foreign','Foreign user','foreign@example.test',true)
              """);
      identity
          .jdbc()
          .execute(
              """
              insert into simpoint_saas_tenants (id,name,owner_id,tenant_type) values
                ('tenant','工作空间','owner','ORGANIZATION'), ('other','Other','foreign','ORGANIZATION')
              """);
      identity
          .jdbc()
          .execute(
              """
              insert into simpoint_ac_roles (id,tenant_id,authority,role_name) values
                ('r1','tenant','approval','部门审批'),('r2','other','approval','Foreign role')
              """);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new TestingAuthenticationToken(
                  new DefaultOAuth2AuthenticatedPrincipal(
                      "workflow",
                      Map.of("client_id", "workflow", "grant_type", "client_credentials"),
                      List.of()),
                  null,
                  "SCOPE_service-router.invoke"));
      var directory = context.getBean(AuditIdentityDirectory.class);
      var request =
          new AuditIdentityRequest(
              "tenant", true, Set.of("owner", "foreign"), Set.of(), Set.of("approval"));
      var labels = directory.resolve(request);
      assertEquals(Map.of("owner", "空间负责人"), labels.users());
      assertEquals(Map.of("approval", "部门审批"), labels.roles());
      assertEquals("工作空间", labels.tenantName());
      identity.jdbc().update("update simpoint_ac_users set enabled=false where id='owner'");
      identity
          .jdbc()
          .update("update simpoint_ac_roles set deleted_at=current_timestamp where id='r1'");
      labels = directory.resolve(request);
      assertTrue(labels.users().isEmpty());
      assertTrue(labels.roles().isEmpty());
      if (split) {
        assertEquals(
            0,
            workflow
                .jdbc()
                .queryForObject(
                    "select count(*) from pg_tables where schemaname=current_schema() and"
                        + " tablename='simpoint_ac_users'",
                    Integer.class));
      }
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
