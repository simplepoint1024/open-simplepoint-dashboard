package org.simplepoint.installation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real selected identity store, migration manifest, offline context, installer journal and restarts. */
@EnabledIfEnvironmentVariable(named = "SP_TENANT_PACKAGE_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class TenantAdoptionInstallationPostgresTest {
  @TempDir Path directory;
  private final String schema = "tenant_install_" + UUID.randomUUID().toString().replace("-", "");
  private final String artifact = "a".repeat(64);
  private JdbcTemplate admin;
  private JdbcTemplate jdbc;
  private Map<String, DataSource> sources;
  private ModulePlan modules;
  private ModulePersistencePlan persistence;
  private StandardEnvironment environment;
  private Properties properties;

  @BeforeEach
  void setup() {
    String url = System.getenv("SP_TENANT_PACKAGE_TEST_POSTGRES_URL");
    String user = System.getenv().getOrDefault("SP_TENANT_PACKAGE_TEST_POSTGRES_USER", "postgres");
    String password = System.getenv().getOrDefault("SP_TENANT_PACKAGE_TEST_POSTGRES_PASSWORD", "");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    admin.execute("create schema " + schema);
    var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
    jdbc = new JdbcTemplate(source);
    sources = Map.of("default", source);
    modules = ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("identity"));
    environment = new StandardEnvironment();
    persistence = ModulePersistencePlan.discover(modules, environment, new DefaultResourceLoader());
    new ModuleMigrationEngine().migrate(ModuleMigrationPlan.discover(modules, persistence.bindings()), sources);
    jdbc.execute("""
        insert into simpoint_saas_tenants (id,name,owner_id,tenant_type,resource_mode,authorization_version)
          values ('legacy-a','Organization','owner-a','ORGANIZATION','PLATFORM',0),
                 ('legacy-b','Personal','owner-b','PERSONAL','PLATFORM',0);
        insert into simpoint_saas_tenant_resource_setup (tenant_id,state)
          values ('legacy-a','ADOPTION_REQUIRED'),('legacy-b','ADOPTION_REQUIRED');
        insert into simpoint_saas_applications (id,code,name,enabled)
          values ('core','simplepoint-core','Core',true),('storage','simplepoint-storage','Storage',true);
        insert into simpoint_ac_resources (id,code,name,type,scope_types,disabled,grantable,public_access,require_org_tenant)
          values ('base','base.view','Base','PAGE','TENANT,PERSONAL',false,true,true,false);
        insert into simpoint_saas_application_resource_rel (id,application_code,resource_code)
          values ('base-member','simplepoint-core','base.view');
        """);
    properties = new Properties();
    properties.setProperty("installation.operation", UUID.randomUUID().toString());
    properties.setProperty("installation.tenants.target", "test/tenant-entitlements");
    properties.setProperty("installation.tenants.expected-generation", "0");
    properties.setProperty("installation.tenants.review-file", directory.resolve("review.json").toString());
    properties.setProperty("installation.journal.store", "default");
  }

  @AfterEach
  void cleanup() {
    if (admin != null) {
      admin.execute("drop schema if exists " + schema + " cascade");
    }
  }

  @Test
  void realOfflineExecutionResumesPartialSuccessAfterRestartWithoutLogin() throws Exception {
    var review = execute(true);
    assertThat(review).containsEntry("tenants", 2).containsEntry("applied", false);
    assertThat(count("simpoint_saas_tenant_resource_credits")).isZero();
    properties.setProperty("installation.tenants.review-fingerprint", review.get("reviewFingerprint").toString());
    jdbc.execute("""
        insert into simpoint_ac_resources (id,code,name,type,scope_types,disabled,grantable,public_access,require_org_tenant)
          values ('later','later.view','Later public resource','PAGE','TENANT,PERSONAL',false,true,true,false);
        alter table simpoint_saas_tenant_resource_setup add constraint reject_second
          check (tenant_id <> 'legacy-b' or state <> 'READY');
        """);
    assertThatThrownBy(() -> execute(false)).hasMessageContaining("ACTION_FAILED");
    assertThat(jdbc.queryForObject("select state from simpoint_saas_tenant_resource_setup where tenant_id='legacy-a'", String.class))
        .isEqualTo("READY");
    assertThat(jdbc.queryForObject("select state from simpoint_saas_tenant_resource_setup where tenant_id='legacy-b'", String.class))
        .isEqualTo("ADOPTION_REQUIRED");
    var first = jdbc.queryForMap("select * from simpoint_saas_tenant_resource_setup where tenant_id='legacy-a'");
    assertThat(first).containsEntry("adopted_by", "installation");
    assertThat(count("simpoint_saas_tenant_resource_credits")).isEqualTo(2);
    jdbc.execute("alter table simpoint_saas_tenant_resource_setup drop constraint reject_second");
    var applied = execute(false);
    assertThat(applied).containsEntry("applied", true).containsEntry("pendingTenantsRemain", false);
    assertThat(count("simpoint_saas_tenant_resource_credits")).isEqualTo(4);
    assertThat(jdbc.queryForMap("select * from simpoint_saas_tenant_resource_setup where tenant_id='legacy-a'")).isEqualTo(first);
    assertThat(jdbc.queryForList("select authorization_version from simpoint_saas_tenants order by id", Long.class))
        .containsExactly(1L, 1L);
    assertThat(execute(false)).isEqualTo(applied);
    assertThat(count("simpoint_saas_tenant_resource_credits")).isEqualTo(4);
    assertThat(count("simpoint_ac_role_resource_grants")).isZero();
    jdbc.update("update simpoint_saas_tenant_resource_setup set adopted_by='changed' where tenant_id='legacy-a'");
    assertThatThrownBy(() -> execute(false)).hasMessageContaining("differs from its durable installation completion");
    assertThat(count("simpoint_saas_tenant_resource_credits")).isEqualTo(4);
  }

  @Test
  void changedInstallationApprovalIsRejectedBeforeAnyTenantWrites() throws Exception {
    execute(true);
    properties.setProperty("installation.tenants.review-fingerprint", "b".repeat(64));
    assertThatThrownBy(() -> execute(false)).hasMessageContaining("differs from the reviewed installation");
    assertThat(count("simpoint_saas_tenant_resource_credits")).isZero();
    assertThat(count("simpoint_saas_tenant_resources")).isZero();
  }

  @Test
  void anUnmarkedTenantIsNotSilentlyOmittedFromTheReviewedMigration() {
    jdbc.update("insert into simpoint_saas_tenants (id,name,owner_id,tenant_type,resource_mode,authorization_version) "
        + "values ('unmarked','Unmarked','owner','ORGANIZATION','PLATFORM',0)");
    assertThatThrownBy(() -> execute(true)).hasMessageContaining("RESOURCE_CONFLICT");
    assertThat(count("simpoint_saas_tenant_resources")).isZero();
    assertThat(count("simpoint_saas_tenant_resource_credits")).isZero();
  }

  private Map<String, Object> execute(boolean preview) throws Exception {
    return TenantAdoptionInstallation.execute(preview, modules, persistence, properties, environment, sources, artifact);
  }

  private long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }
}
