package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.data.migration.ModuleMigrationPlan.ModuleSchema;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.rbac.tenant.api.service.MemberLabelDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Label;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Request;
import org.simplepoint.plugin.rbac.tenant.service.impl.MemberLabelDirectoryImpl;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real source migration, snapshot transactions and storage ownership; no consumer projection yet.
 */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class MemberLabelDirectoryPostgresTest {
  private static final String REVISION_TABLE = "simpoint_identity_member_label_revision";

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void boundsPagesAndFencesChangesInSharedAndSplitStores(boolean split) {
    try (var fixture = new Fixture(split)) {
      serviceIdentity("client_credentials");
      var directory = fixture.directory;
      var first = directory.page(new Request("tenant", null, null, 2));
      assertEquals(List.of(new Label("a", "张三"), new Label("b", "山田 太郎")), first.labels());
      assertEquals("b", first.nextUserId());
      var second = directory.page(new Request("tenant", first.revision(), "b", 2));
      assertEquals(
          List.of(new Label("c", "c@example.test"), new Label("d", null)), second.labels());
      assertEquals(null, second.nextUserId());
      assertTrue(second.tenantActive());
      assertFalse(directory.page(new Request("missing", null, null, 200)).tenantActive());
      fixture.jdbc.update("update simpoint_ac_users set name='新名称' where id='a'");
      var stale = directory.page(new Request("tenant", first.revision(), "b", 2));
      assertTrue(stale.restartRequired());
      assertTrue(stale.labels().isEmpty());
      fixture.jdbc.update(
          "update simpoint_saas_tenant_user_rel set enabled=false where user_id='b'");
      fixture.jdbc.update("update simpoint_ac_users set deleted_at=now() where id='c'");
      assertEquals(
          List.of(new Label("a", "新名称"), new Label("d", null)),
          directory.page(new Request("tenant", null, null, 200)).labels());
      fixture.jdbc.update("update simpoint_saas_tenants set owner_id='foreign' where id='tenant'");
      assertEquals(
          List.of(new Label("d", null), new Label("foreign", "Other")),
          directory.page(new Request("tenant", null, null, 200)).labels());
      fixture.jdbc.update("update simpoint_saas_tenants set deleted_at=now() where id='tenant'");
      assertFalse(directory.page(new Request("tenant", null, null, 200)).tenantActive());
      assertEquals(
          0,
          fixture
              .other
              .jdbc()
              .queryForObject(
                  "select count(*) from pg_tables where schemaname=current_schema() and"
                      + " tablename=?",
                  Integer.class,
                  REVISION_TABLE));
      SecurityContextHolder.getContext()
          .setAuthentication(
              new TestingAuthenticationToken("workflow", "", "SCOPE_service-router.invoke"));
      assertThrows(AccessDeniedException.class, directory::revision);
      serviceIdentity("authorization_code");
      assertThrows(
          AccessDeniedException.class, () -> directory.page(new Request("tenant", null, null, 1)));
      SecurityContextHolder.clearContext();
      assertThrows(AccessDeniedException.class, directory::revision);
    }
  }

  @Test
  void allSourceChangesAdvanceRevisionButUnrelatedAccountUpdatesAndRollbacksDoNot() {
    try (var fixture = new Fixture(false)) {
      serviceIdentity("client_credentials");
      var initial = fixture.directory.revision();
      fixture.jdbc.update(
          "update simpoint_ac_users set password='test-only',authorization_version=8");
      fixture.jdbc.update("update simpoint_saas_tenants set authorization_version=8");
      assertEquals(initial, fixture.directory.revision());
      var tx = new TransactionTemplate(new DataSourceTransactionManager(fixture.identity.source()));
      tx.executeWithoutResult(
          status -> {
            fixture.jdbc.update("update simpoint_ac_users set name='rolled back' where id='a'");
            assertEquals(initial.sequence() + 1, sequence(fixture.jdbc));
            status.setRollbackOnly();
          });
      assertEquals(initial, fixture.directory.revision());
      for (String column : List.of("name", "family_name", "middle_name", "given_name", "email")) {
        long previous = sequence(fixture.jdbc);
        fixture.jdbc.update(
            "update simpoint_ac_users set " + column + "=? where id='a'", "changed");
        assertEquals(previous + 1, sequence(fixture.jdbc), column);
      }
      for (String mutation :
          List.of(
              "update simpoint_ac_users set enabled=false where id='a'",
              "update simpoint_ac_users set deleted_at=now() where id='a'",
              "update simpoint_ac_users set id='a-new' where id='a'",
              "delete from simpoint_ac_users where id='a-new'",
              "insert into simpoint_ac_users(id,name) values ('new','new')",
              "update simpoint_saas_tenants set owner_id='new' where id='tenant'",
              "update simpoint_saas_tenants set deleted_at=now() where id='tenant'",
              "update simpoint_saas_tenants set id='new-tenant' where id='tenant'",
              "delete from simpoint_saas_tenants where id='new-tenant'",
              "update simpoint_saas_tenant_user_rel set enabled=false where user_id='b'",
              "update simpoint_saas_tenant_user_rel set deleted_at=now() where user_id='b'",
              "update simpoint_saas_tenant_user_rel set tenant_id='other' where user_id='b'",
              "update simpoint_saas_tenant_user_rel set user_id='new' where user_id='b'",
              "delete from simpoint_saas_tenant_user_rel where user_id='new'",
              "truncate simpoint_saas_tenant_user_rel",
              "truncate simpoint_saas_tenants",
              "truncate simpoint_ac_users cascade")) {
        long previous = sequence(fixture.jdbc);
        fixture.jdbc.execute(mutation);
        assertTrue(sequence(fixture.jdbc) > previous, mutation);
      }
      assertEquals(initial.epoch(), fixture.directory.revision().epoch());
      fixture.jdbc.execute("delete from " + REVISION_TABLE);
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> fixture.jdbc.update("insert into simpoint_ac_users(id) values ('must-fail')"));
      assertThrows(org.springframework.dao.DataAccessException.class, fixture.directory::revision);
    }
  }

  @Test
  void midPageCommitCannotMixRevisionAndNames() {
    try (var fixture = new Fixture(true)) {
      serviceIdentity("client_credentials");
      var before = fixture.directory.revision();
      fixture.afterRevisionRead.set(
          () ->
              fixture.jdbc.update(
                  "update simpoint_ac_users set name='after snapshot' where id='a'"));
      var page = fixture.directory.page(new Request("tenant", before, null, 200));
      assertEquals(before, page.revision());
      assertEquals("张三", page.labels().getFirst().name());
      assertNotEquals(before, fixture.directory.revision());
      assertTrue(
          fixture.directory.page(new Request("tenant", before, null, 200)).restartRequired());
    }
  }

  @Test
  void concurrentMutationsCannotLoseRevisionOrExposeUncommittedDirectoryChanges() throws Exception {
    try (var fixture = new Fixture(false);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      serviceIdentity("client_credentials");
      long before = sequence(fixture.jdbc);
      var written = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(fixture.identity.source()));
      var first =
          executor.submit(
              () ->
                  tx.executeWithoutResult(
                      status -> {
                        fixture.jdbc.update(
                            "update simpoint_ac_users set name='first' where id='a'");
                        written.countDown();
                        await(release);
                      }));
      try {
        assertTrue(written.await(10, TimeUnit.SECONDS));
        assertEquals(before, sequence(fixture.jdbc));
        assertEquals(
            "张三",
            fixture
                .directory
                .page(new Request("tenant", null, null, 1))
                .labels()
                .getFirst()
                .name());
        var second =
            executor.submit(
                () ->
                    fixture.jdbc.update("update simpoint_ac_users set name='second' where id='b'"));
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        assertEquals(1, second.get(10, TimeUnit.SECONDS));
        assertEquals(before + 2, sequence(fixture.jdbc));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void upgradesTrackedBaselineWithoutRewritingUserDataAndRetainsEpochOnRestart() {
    var modules = ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("identity"));
    var persistence =
        ModulePersistencePlan.discover(
            modules, new StandardEnvironment(), new DefaultResourceLoader());
    var latest = ModuleMigrationPlan.discover(modules, persistence.bindings());
    var identity =
        latest.schemas().stream()
            .filter(schema -> schema.module().equals("identity"))
            .findFirst()
            .orElseThrow();
    var previous =
        new ModuleMigrationPlan(
            List.of(
                new ModuleSchema(
                    identity.module(),
                    identity.store(),
                    identity.historyTable(),
                    identity.tables().stream()
                        .filter(
                            table ->
                                !table.startsWith("simpoint_identity_member_label")
                                    && !table.equals("simpoint_resource_catalog_revision")
                                    && !table.startsWith("simpoint_resource_registration_"))
                        .toList(),
                    Map.of("postgresql", identity.scripts().get("postgresql").subList(0, 1)))));
    var engine = new ModuleMigrationEngine();
    try (var database = new MigrationPostgresDatabase();
        var other = new MigrationPostgresDatabase()) {
      engine.migrate(previous, Map.of("default", database.source()));
      seed(database.jdbc());
      assertEquals(
          4,
          engine.migrate(latest, Map.of("default", database.source())).stream()
              .filter(result -> result.module().equals("identity"))
              .findFirst()
              .orElseThrow()
              .applied());
      assertEquals(
          database.jdbc().queryForObject("select count(*) from simpoint_saas_tenants", Long.class),
          database
              .jdbc()
              .queryForObject(
                  "select count(*) from simpoint_identity_member_label_observations", Long.class));
      var epoch =
          database.jdbc().queryForObject("select epoch from " + REVISION_TABLE, String.class);
      assertEquals(
          "张三",
          database
              .jdbc()
              .queryForObject("select name from simpoint_ac_users where id='a'", String.class));
      assertTrue(
          engine.migrate(latest, Map.of("default", database.source())).stream()
              .allMatch(result -> result.applied() == 0));
      assertEquals(
          epoch,
          database.jdbc().queryForObject("select epoch from " + REVISION_TABLE, String.class));
      engine.migrate(latest, Map.of("default", other.source()));
      assertNotEquals(
          epoch, other.jdbc().queryForObject("select epoch from " + REVISION_TABLE, String.class));
    }
  }

  private static long sequence(JdbcTemplate jdbc) {
    return jdbc.queryForObject("select revision from " + REVISION_TABLE, Long.class);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for test transaction");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private static void serviceIdentity(String grant) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(
                new DefaultOAuth2AuthenticatedPrincipal(
                    "workflow", Map.of("client_id", "workflow", "grant_type", grant), List.of()),
                null,
                "SCOPE_service-router.invoke"));
  }

  private static void seed(JdbcTemplate jdbc) {
    jdbc.execute(
        """
        insert into simpoint_ac_users(id,name,family_name,given_name,email,enabled) values
        ('a','张三',null,null,null,true), ('b',null,'山田','太郎',null,true),
        ('c',null,null,null,'c@example.test',true), ('d',null,null,null,null,true),
        ('disabled','Disabled',null,null,null,false), ('foreign','Other',null,null,null,true)
        """);
    jdbc.execute(
        """
        insert into simpoint_saas_tenants(id,name,owner_id,tenant_type) values
        ('tenant','Tenant','a','ORGANIZATION'),('other','Other','foreign','ORGANIZATION')
        """);
    jdbc.execute(
        """
        insert into simpoint_saas_tenant_user_rel(id,tenant_id,user_id) values
        ('mb','tenant','b'),('mc','tenant','c'),('md','tenant','d'),('mx','tenant','disabled')
        """);
  }

  private static final class Fixture implements AutoCloseable {
    final MigrationPostgresDatabase identity = new MigrationPostgresDatabase();
    final MigrationPostgresDatabase other = new MigrationPostgresDatabase();
    final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    final AtomicReference<Runnable> afterRevisionRead = new AtomicReference<>();
    final JdbcTemplate jdbc = identity.jdbc();
    final MemberLabelDirectory directory;

    Fixture(boolean split) {
      try {
        var modules =
            ModuleCatalog.discover(getClass().getClassLoader())
                .plan(List.of("identity", "workflow"));
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
                    ? Map.of("identity", identity.source(), "workflow", other.source())
                    : Map.of("default", identity.source()));
        DataSource observed = new ObservedSource(identity.source(), afterRevisionRead);
        context.registerBean(
            split ? "identitySource" : "dataSource", DataSource.class, () -> observed);
        if (split) {
          context.registerBean(
              "workflowSource",
              DataSource.class,
              other::source,
              definition -> definition.setPrimary(true));
        }
        context.registerBean(ModulePlan.class, () -> modules);
        context.register(
            IdentityLabelsStoragePostgresTest.Persistence.class, MemberLabelDirectoryImpl.class);
        context.refresh();
        seed(jdbc);
        directory = context.getBean(MemberLabelDirectory.class);
      } catch (RuntimeException | Error exception) {
        close();
        throw exception;
      }
    }

    @Override
    public void close() {
      context.close();
      other.close();
      identity.close();
    }
  }

  /** Injects a committed write after the revision SELECT has established the source snapshot. */
  private static final class ObservedSource extends DelegatingDataSource {
    private final AtomicReference<Runnable> action;

    ObservedSource(DataSource source, AtomicReference<Runnable> action) {
      super(source);
      this.action = action;
    }

    @Override
    public Connection getConnection() throws SQLException {
      var connection = super.getConnection();
      return proxy(
          Connection.class,
          connection,
          (method, result, args) ->
              method.equals("createStatement")
                  ? proxy(
                      Statement.class,
                      (Statement) result,
                      (operation, value, parameters) -> {
                        if (operation.equals("executeQuery")
                            && parameters[0].toString().contains(REVISION_TABLE)) {
                          var callback = action.getAndSet(null);
                          if (callback != null) {
                            callback.run();
                          }
                        }
                        return value;
                      })
                  : result);
    }
  }

  private interface AfterCall {
    Object apply(String method, Object result, Object[] args);
  }

  private static <T> T proxy(Class<T> type, T delegate, AfterCall after) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              try {
                return after.apply(method.getName(), method.invoke(delegate, args), args);
              } catch (InvocationTargetException exception) {
                throw exception.getCause();
              }
            }));
  }
}
