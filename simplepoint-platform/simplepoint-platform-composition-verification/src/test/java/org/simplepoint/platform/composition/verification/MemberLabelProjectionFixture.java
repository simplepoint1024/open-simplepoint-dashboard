package org.simplepoint.platform.composition.verification;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.rbac.tenant.api.service.MemberLabelDirectory;
import org.simplepoint.plugin.rbac.tenant.service.impl.MemberLabelDirectoryImpl;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelProjection;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelProperties;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual versioned identity source and workflow-owned projection in shared or separate stores. */
final class MemberLabelProjectionFixture implements AutoCloseable {
  private static final String STATE = "simpoint_workflow_member_label_state";
  private static final String LABELS = "simpoint_workflow_member_labels";
  private static final Duration LEASE = Duration.ofSeconds(60);
  final MigrationPostgresDatabase identity = new MigrationPostgresDatabase();
  final MigrationPostgresDatabase workflow = new MigrationPostgresDatabase();
  final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
  final JdbcTemplate jdbc;
  final WorkflowMemberLabelStore store;
  final MemberLabelDirectory directory;
  final PlatformTransactionManager workflowTransactions;

  MemberLabelProjectionFixture(boolean split, int members) {
    try {
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
          split ? "identitySource" : "dataSource",
          DataSource.class,
          identity::source,
          definition -> definition.setPrimary(true));
      if (split) {
        context.registerBean("workflowSource", DataSource.class, workflow::source);
      }
      context.registerBean(ModulePlan.class, () -> modules);
      context.register(
          IdentityLabelsStoragePostgresTest.Persistence.class,
          MemberLabelDirectoryImpl.class,
          WorkflowMemberLabelStore.class);
      context.refresh();
      jdbc = (split ? workflow : identity).jdbc();
      store = context.getBean(WorkflowMemberLabelStore.class);
      directory = context.getBean(MemberLabelDirectory.class);
      workflowTransactions =
          context.getBean(
              persistence.bindings().require("workflow").transactionManager(),
              PlatformTransactionManager.class);
      identity
          .jdbc()
          .update(
              "insert into simpoint_saas_tenants(id,name,owner_id,tenant_type) values"
                  + " ('tenant','Tenant','u0000','ORGANIZATION')");
      identity
          .jdbc()
          .update(
              "insert into simpoint_ac_users(id,name,enabled) select"
                  + " 'u'||lpad(i::text,4,'0'),'成员'||lpad(i::text,4,'0'),true from"
                  + " generate_series(0,?) i",
              members - 1);
      identity
          .jdbc()
          .update(
              "insert into simpoint_saas_tenant_user_rel(id,tenant_id,user_id) select"
                  + " 'm'||i,'tenant','u'||lpad(i::text,4,'0') from generate_series(1,?) i",
              members - 1);
      serviceIdentity();
    } catch (RuntimeException | Error failure) {
      close();
      throw failure;
    }
  }

  WorkflowMemberLabelProjection projection(
      MemberLabelDirectory source, int pagesPerRun, int pagesPerBuild) {
    return new WorkflowMemberLabelProjection(
        store,
        source,
        new WorkflowMemberLabelProperties(
            pagesPerRun, pagesPerBuild, 1, Duration.ofSeconds(10), LEASE));
  }

  long rows() {
    return jdbc.queryForObject("select count(*) from " + LABELS, Long.class);
  }

  long activeRows() {
    return jdbc.queryForObject(
        "select count(*) from "
            + LABELS
            + " l join "
            + STATE
            + " s on s.tenant_id=l.tenant_id and s.active_generation=l.generation",
        Long.class);
  }

  String activeName(String user) {
    return jdbc.queryForObject(
        "select name from "
            + LABELS
            + " l join "
            + STATE
            + " s on s.tenant_id=l.tenant_id and s.active_generation=l.generation where"
            + " l.user_id=?",
        String.class,
        user);
  }

  @Override
  public void close() {
    context.close();
    workflow.close();
    identity.close();
  }

  static void serviceIdentity() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(
                new DefaultOAuth2AuthenticatedPrincipal(
                    "workflow",
                    Map.of("client_id", "workflow", "grant_type", "client_credentials"),
                    List.of()),
                null,
                "SCOPE_service-router.invoke"));
  }
}
