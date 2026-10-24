package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.data.migration.ModuleMigrationPlan.ModuleSchema;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.oidc.api.error.ExternalIdentityBindingException;
import org.simplepoint.plugin.oidc.api.error.ExternalIdentityBindingException.Reason;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityBinding;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityAccountService;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.simplepoint.plugin.oidc.service.repository.JpaExternalIdentityLinkRepository;
import org.simplepoint.plugin.oidc.service.repository.JpaExternalIdentityProviderRepository;
import org.simplepoint.plugin.oidc.service.security.ExternalIdentityProviderCredentialCipher;
import org.simplepoint.plugin.oidc.service.service.impl.ExternalIdentityAccountServiceImpl;
import org.simplepoint.plugin.oidc.service.service.impl.ExternalIdentityProviderServiceImpl;
import org.simplepoint.plugin.oidc.service.support.ExternalIdentityClientRegistrationFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real OIDC transactions, concurrent binding ownership and historical revocation on PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class ExternalIdentityBindingPostgresTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bindingsFollowOidcStoreAndPreserveRevocationAcrossRebinding(boolean split) {
    try (var fixture = new Fixture(split)) {
      var service = fixture.service;
      fixture.tx.executeWithoutResult(
          status -> {
            service.bind("provider", "rolled-back", null, "user");
            status.setRollbackOnly();
          });
      assertTrue(service.find("provider", "rolled-back").isEmpty());
      var first = service.bind("provider", "first", "member@example.invalid", "user");
      assertEquals(first, service.bind("provider", "first", "changed@example.invalid", "user"));
      service.recordLogin(first);
      assertNotNull(service.connections("user").getFirst().lastLoginAt());
      assertEquals("member@example.invalid", service.connections("user").getFirst().email());
      assertTrue(service.isLinked("user", "example"));
      assertReason(Reason.SUBJECT_IN_USE, () -> service.bind("provider", "first", null, "other"));
      assertReason(Reason.ACCOUNT_IN_USE, () -> service.bind("provider", "other", null, "user"));
      assertThrows(
          IllegalStateException.class, () -> fixture.providers.removeByIds(List.of("provider")));
      fixture.tx.executeWithoutResult(
          status -> {
            service.unlink("user", "example");
            status.setRollbackOnly();
          });
      assertTrue(service.isLinked("user", "example"));
      service.unlink("user", "example");
      var replacement = service.bind("provider", "second", null, "user");
      assertNotEquals(first.id(), replacement.id());
      assertReason(Reason.BINDING_REVOKED, () -> service.recordLogin(first));
      assertNull(service.connections("user").getFirst().lastLoginAt());
      assertEquals(
          1L,
          fixture.jdbc.queryForObject(
              "select count(*) from simpoint_ac_external_identity_link where deleted_at is not"
                  + " null",
              Long.class));
      service.recordLogin(replacement);
      fixture.jdbc.update("update simpoint_ac_external_identity_provider set enabled=false");
      assertReason(
          Reason.PROVIDER_UNAVAILABLE, () -> service.bind("provider", "third", null, "third"));
      assertReason(Reason.BINDING_REVOKED, () -> service.recordLogin(replacement));
      assertTrue(service.connections("user").getFirst().linked());
      assertFalse(service.connections("user").getFirst().enabled());
      service.unlink("user", "example");
      assertTrue(service.connections("user").isEmpty());
      fixture.jdbc.update("update simpoint_ac_external_identity_provider set enabled=true");
      assertFalse(service.isLinked("user", "example"));
      var again = service.bind("provider", "first", null, "user");
      assertNotEquals(first.id(), again.id());
      assertReason(Reason.BINDING_REVOKED, () -> service.recordLogin(first));
      assertReason(
          Reason.INVALID_INPUT, () -> service.bind("provider", "漢".repeat(513), null, "x"));
      assertNotNull(service.bind("provider", "漢".repeat(512), null, "unicode-user"));
      verifyNoInteractions(fixture.foreignSource, fixture.foreignTransactions);
      if (split) {
        assertNull(
            fixture
                .identity
                .jdbc()
                .queryForObject(
                    "select to_regclass('simpoint_ac_external_identity_link')::text",
                    String.class));
        assertNull(
            fixture.jdbc.queryForObject(
                "select to_regclass('simpoint_ac_users')::text", String.class));
      }
    }
  }

  @Test
  void concurrentBindingsAreIdempotentAndConflictsNeverTransferOwnership() throws Exception {
    try (var fixture = new Fixture(true)) {
      var service = fixture.service;
      List<ExternalIdentityBinding> same =
          concurrent(
              List.of(
                  () -> service.bind("provider", "same", null, "same-user"),
                  () -> service.bind("provider", "same", null, "same-user"),
                  () -> service.bind("provider", "same", null, "same-user")));
      assertEquals(1, same.stream().distinct().count());
      var subjectRace =
          concurrent(
              List.of(
                  () -> outcome(() -> service.bind("provider", "contested", null, "account-a")),
                  () -> outcome(() -> service.bind("provider", "contested", null, "account-b"))));
      assertEquals(1, subjectRace.stream().filter("OK"::equals).count());
      assertTrue(subjectRace.contains(Reason.SUBJECT_IN_USE.name()));
      var userRace =
          concurrent(
              List.of(
                  () ->
                      outcome(() -> service.bind("provider", "subject-a", null, "contested-user")),
                  () ->
                      outcome(
                          () -> service.bind("provider", "subject-b", null, "contested-user"))));
      assertEquals(1, userRace.stream().filter("OK"::equals).count());
      assertTrue(userRace.contains(Reason.ACCOUNT_IN_USE.name()));
      assertEquals(
          3L,
          fixture.jdbc.queryForObject(
              "select count(*) from simpoint_ac_external_identity_link where deleted_at is null",
              Long.class));
      var loginAndUnlink =
          concurrent(
              List.of(
                  () -> outcome(() -> service.recordLogin(same.getFirst())),
                  () -> outcome(() -> service.unlink("same-user", "example"))));
      assertTrue(
          loginAndUnlink.stream()
              .allMatch(
                  value -> value.equals("OK") || value.equals(Reason.BINDING_REVOKED.name())));
      if (loginAndUnlink.getFirst().equals("OK")) {
        assertNotNull(
            fixture.jdbc.queryForObject(
                "select last_login_at from simpoint_ac_external_identity_link where id=?",
                java.time.OffsetDateTime.class,
                same.getFirst().id()));
      }
      assertTrue(service.find("provider", "same").isEmpty());
      assertEquals(
          1L,
          fixture.jdbc.queryForObject(
              "select count(*) from simpoint_ac_external_identity_link where deleted_at is not"
                  + " null",
              Long.class));
      // The transaction manager still accepts work after both expected conflicts.
      service.bind("provider", "after-conflict", null, "after-user");
      assertTrue(service.find("provider", "after-conflict").isPresent());
    }
  }

  @Test
  void providerRemovalAndBindingCannotLeaveAnEffectiveOrphan() throws Exception {
    try (var fixture = new Fixture(true)) {
      var results =
          concurrent(
              List.of(
                  () -> outcome(() -> fixture.service.bind("provider", "new", null, "user")),
                  () -> {
                    try {
                      fixture.providers.removeByIds(List.of("provider"));
                      return "DELETED";
                    } catch (IllegalStateException inUse) {
                      return "IN_USE";
                    }
                  }));
      assertTrue(
          results.equals(List.of("OK", "IN_USE"))
              || results.equals(List.of(Reason.PROVIDER_UNAVAILABLE.name(), "DELETED")));
      assertEquals(
          0L,
          fixture.jdbc.queryForObject(
              "select count(*) from simpoint_ac_external_identity_link l where l.deleted_at is null"
                  + " and not exists (select 1 from simpoint_ac_external_identity_provider p"
                  + " where p.id=l.provider_id and p.deleted_at is null)",
              Long.class));
    }
  }

  @Test
  void versionThreePreservesHistoryAndOnlyRelaxesArchivedUniqueness() {
    var modules = ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("oidc"));
    var persistence =
        ModulePersistencePlan.discover(
            modules, new StandardEnvironment(), new DefaultResourceLoader());
    var latest = ModuleMigrationPlan.discover(modules, persistence.bindings());
    var schema = latest.schemas().getFirst();
    var before =
        new ModuleMigrationPlan(
            List.of(
                new ModuleSchema(
                    schema.module(),
                    schema.store(),
                    schema.historyTable(),
                    schema.tables(),
                    Map.of("postgresql", schema.scripts().get("postgresql").subList(0, 2)))));
    var engine = new ModuleMigrationEngine();
    try (var upgraded = new MigrationPostgresDatabase();
        var other = new MigrationPostgresDatabase()) {
      engine.migrate(before, Map.of("default", upgraded.source()));
      engine.migrate(before, Map.of("default", other.source()));
      var jdbc = upgraded.jdbc();
      provider(jdbc);
      jdbc.update(
          "insert into simpoint_ac_external_identity_link"
              + " (id,provider_id,external_subject,user_id,deleted_at) values"
              + " ('old','provider','subject','user',now())");
      final var unchanged = other.physicalContract("simpoint_ac_external_identity_%");
      assertThrows(
          DataIntegrityViolationException.class,
          () -> insertBinding(jdbc, "new", "subject", "user"));
      assertEquals(
          1, engine.migrate(latest, Map.of("default", upgraded.source())).getFirst().applied());
      insertBinding(jdbc, "new", "subject", "user");
      assertThrows(
          DataIntegrityViolationException.class,
          () -> insertBinding(jdbc, "duplicate-subject", "subject", "other"));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> insertBinding(jdbc, "duplicate-user", "other", "user"));
      assertThrows(
          DataIntegrityViolationException.class,
          () ->
              jdbc.update(
                  "update simpoint_ac_external_identity_link set deleted_at=null where id='old'"));
      assertEquals(
          1L,
          jdbc.queryForObject(
              "select count(*) from simpoint_ac_external_identity_link where deleted_at is not"
                  + " null",
              Long.class));
      assertEquals(unchanged, other.physicalContract("simpoint_ac_external_identity_%"));
      assertEquals(
          0, engine.migrate(latest, Map.of("default", upgraded.source())).getFirst().applied());
    }
  }

  private static void insertBinding(JdbcTemplate jdbc, String id, String subject, String user) {
    jdbc.update(
        "insert into simpoint_ac_external_identity_link (id,provider_id,external_subject,user_id)"
            + " values (?,'provider',?,?)",
        id,
        subject,
        user);
  }

  private static void assertReason(Reason reason, Runnable action) {
    assertEquals(
        reason, assertThrows(ExternalIdentityBindingException.class, action::run).getReason());
  }

  private static String outcome(Runnable action) {
    try {
      action.run();
      return "OK";
    } catch (ExternalIdentityBindingException failure) {
      return failure.getReason().name();
    }
  }

  private static <T> List<T> concurrent(List<Callable<T>> operations) throws Exception {
    var barrier = new CyclicBarrier(operations.size());
    var executor = Executors.newFixedThreadPool(operations.size());
    try {
      var futures = new ArrayList<Future<T>>();
      for (var operation : operations) {
        futures.add(
            executor.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  return operation.call();
                }));
      }
      var results = new ArrayList<T>();
      for (var future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  private static void provider(JdbcTemplate jdbc) {
    jdbc.update(
        """
        insert into simpoint_ac_external_identity_provider
          (id,registration_id,display_name,preset,protocol,client_id,client_authentication_method,
           scopes,user_name_attribute,subject_claim,match_strategy,require_verified_email,
           allow_private_network,sort_order,enabled)
        values ('provider','example','Example','CUSTOM','OIDC','client','none',
                'openid','sub','sub','VERIFIED_EMAIL',true,false,0,true)
        """);
  }

  private static final class Fixture implements AutoCloseable {
    private final MigrationPostgresDatabase identity = new MigrationPostgresDatabase();
    private final MigrationPostgresDatabase oidc = new MigrationPostgresDatabase();
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();
    private final DataSource foreignSource = mock(DataSource.class);
    private final PlatformTransactionManager foreignTransactions =
        mock(PlatformTransactionManager.class);
    private final ExternalIdentityAccountService service;
    private final ExternalIdentityProviderService providers;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    private Fixture(boolean split) {
      try {
        var modules =
            ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("identity", "oidc"));
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
                            "simplepoint.persistence.modules.oidc",
                            "oidc",
                            "simplepoint.persistence.sources.oidc",
                            "oidcSource")
                        : Map.of()));
        var persistence =
            ModulePersistencePlan.discover(
                modules, context.getEnvironment(), new DefaultResourceLoader());
        new ModuleMigrationEngine()
            .migrate(
                ModuleMigrationPlan.discover(modules, persistence.bindings()),
                split
                    ? Map.of("identity", identity.source(), "oidc", oidc.source())
                    : Map.of("default", identity.source()));
        context.registerBean(
            split ? "identitySource" : "dataSource", DataSource.class, identity::source);
        if (split) {
          context.registerBean("oidcSource", DataSource.class, oidc::source);
        }
        context.registerBean(
            "foreignSource",
            DataSource.class,
            () -> foreignSource,
            definition -> definition.setPrimary(true));
        context.registerBean(
            "transactionManager",
            PlatformTransactionManager.class,
            () -> foreignTransactions,
            definition -> definition.setPrimary(true));
        context.registerBean(ModulePlan.class, () -> modules);
        context.registerBean(
            ExternalIdentityProviderServiceImpl.class,
            () ->
                new ExternalIdentityProviderServiceImpl(
                    context.getBean(JpaExternalIdentityProviderRepository.class),
                        mock(DetailsProviderService.class),
                    context.getBean(JpaExternalIdentityLinkRepository.class),
                        mock(ExternalIdentityProviderCredentialCipher.class),
                    mock(ExternalIdentityClientRegistrationFactory.class),
                        context.getEnvironment()));
        context.register(Persistence.class, ExternalIdentityAccountServiceImpl.class);
        context.refresh();
        service = context.getBean(ExternalIdentityAccountService.class);
        providers = context.getBean(ExternalIdentityProviderService.class);
        tx =
            new TransactionTemplate(
                context.getBean(
                    persistence.bindings().require("oidc").transactionManager(),
                    PlatformTransactionManager.class));
        jdbc = split ? oidc.jdbc() : identity.jdbc();
        provider(jdbc);
      } catch (RuntimeException | Error failure) {
        close();
        throw failure;
      }
    }

    @Override
    public void close() {
      context.close();
      oidc.close();
      identity.close();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
