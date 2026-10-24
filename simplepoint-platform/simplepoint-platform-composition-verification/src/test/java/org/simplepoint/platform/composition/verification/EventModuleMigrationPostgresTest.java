package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.composition.ModuleEventPlan;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.EventRetryPolicy;
import org.simplepoint.events.jdbc.JdbcEventStore;
import org.simplepoint.events.migration.ModuleEventMigrations;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies migrated ledgers against real module JPA transactions; delivery is driven in process.
 */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class EventModuleMigrationPostgresTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void migratedLedgerPreservesJpaAtomicityReceiptDeduplicationAndRestart(boolean split) {
    try (var identity = new MigrationPostgresDatabase();
        var app = new MigrationPostgresDatabase();
        var context = new AnnotationConfigApplicationContext()) {
      if (split) {
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(
                new MapPropertySource(
                    "split",
                    Map.of(
                        "simplepoint.persistence.modules.app-form",
                        "app",
                        "simplepoint.persistence.sources.app",
                        "appSource")));
        context.registerBean("appSource", DataSource.class, app::source);
      }
      context.registerBean("dataSource", DataSource.class, identity::source);
      var modules =
          ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("identity", "app-form"));
      var persistence =
          ModulePersistencePlan.discover(
              modules, context.getEnvironment(), new DefaultResourceLoader());
      var plan = ModuleMigrationPlan.discover(modules, persistence.bindings());
      Map<String, DataSource> sources =
          split
              ? Map.of("default", identity.source(), "app", app.source())
              : Map.of("default", identity.source());
      var infrastructure =
          plan.schemas().stream()
              .filter(schema -> schema.module().equals(ModuleEventMigrations.OWNER))
              .toList();
      assertEquals(split ? 2 : 1, infrastructure.size());
      assertEquals(
          1L,
          infrastructure.stream()
              .map(ModuleMigrationPlan.ModuleSchema::historyTable)
              .distinct()
              .count());
      var migrations = new ModuleMigrationEngine();
      assertTrue(
          migrations.migrate(plan, sources).stream()
              .allMatch(
                  result -> result.applied() == (result.module().equals("identity") ? 5 : 1)));
      context.registerBean(ModulePlan.class, () -> modules);
      context.register(Persistence.class);
      context.refresh();
      var identities = ModuleEventPlan.from(modules);
      var identityBinding = persistence.bindings().require("identity");
      var appBinding = persistence.bindings().require("app-form");
      var identityManager =
          context.getBean(identityBinding.transactionManager(), PlatformTransactionManager.class);
      var appManager =
          context.getBean(appBinding.transactionManager(), PlatformTransactionManager.class);
      var sender =
          store(
              sources.get(identityBinding.store()),
              identityManager,
              identities.channels().get("identity").identity());
      final var receiver =
          store(
              sources.get(appBinding.store()),
              appManager,
              identities.channels().get("app-form").identity());
      var event = event(identities.channels().get("identity").identity());
      final var completion = event(identities.channels().get("app-form").identity());
      var em = context.getBean(identityBinding.entityManager(), EntityManager.class);
      var transaction = new TransactionTemplate(identityManager);
      assertThrows(IllegalStateException.class, () -> sender.publish(event));
      transaction.executeWithoutResult(
          status -> {
            em.persist(grant("rolled-back"));
            em.flush();
            sender.publish(event);
            status.setRollbackOnly();
          });
      assertEquals(
          0L,
          identity
              .jdbc()
              .queryForObject("select count(*) from simpoint_ac_role_resource_grants", Long.class));
      assertEquals(0, sender.count("OUTBOX", "PENDING"));
      transaction.executeWithoutResult(
          status -> {
            em.persist(grant("committed"));
            em.flush();
            sender.publish(event);
          });
      assertEquals(
          1L,
          identity
              .jdbc()
              .queryForObject("select count(*) from simpoint_ac_role_resource_grants", Long.class));
      if (split) {
        assertThrows(
            IllegalStateException.class,
            () -> transaction.executeWithoutResult(status -> receiver.publish(completion)));
      }
      var outgoing = sender.claim("OUTBOX");
      assertNotNull(outgoing);
      var subscription =
          new EventSubscription("migration.receive", event.producer(), event.eventType(), 1);
      receiver.receive(subscription, outgoing.event());
      receiver.receive(subscription, outgoing.event());
      sender.sent(outgoing);
      assertEquals(1, receiver.count("INBOX", "PENDING"));
      var incoming = receiver.claim("INBOX");
      assertNotNull(incoming);
      var appJdbc = new JdbcTemplate(sources.get(appBinding.store()));
      Runnable effect =
          () -> {
            appJdbc.update(
                "insert into simpoint_app_definitions "
                    + "(id,tenant_id,name,status,main_page_id,created_at,updated_at) "
                    + "values ('event-fixture','tenant-a','事件安装验证','DRAFT','page',now(),now())");
            receiver.publish(completion);
          };
      assertThrows(
          IllegalStateException.class,
          () ->
              receiver.process(
                  incoming,
                  () -> {
                    effect.run();
                    throw new IllegalStateException("rollback effect and completion");
                  }));
      assertEquals(
          0L, appJdbc.queryForObject("select count(*) from simpoint_app_definitions", Long.class));
      assertEquals(0, receiver.count("OUTBOX", "PENDING"));
      assertEquals(1, receiver.count("INBOX", "PROCESSING"));
      receiver.process(incoming, effect);
      receiver.process(
          incoming,
          () -> {
            throw new AssertionError("Completed inbox replayed");
          });
      assertEquals(
          1L, appJdbc.queryForObject("select count(*) from simpoint_app_definitions", Long.class));
      assertEquals(1, receiver.count("INBOX", "DONE"));
      assertEquals(1, receiver.count("OUTBOX", "PENDING"));
      assertTrue(
          migrations.migrate(plan, sources).stream().allMatch(result -> result.applied() == 0));
      migrations.verify(plan, sources);
      assertEquals(1, sender.count("OUTBOX", "DONE"));
      assertEquals(1, receiver.count("INBOX", "DONE"));
      assertEquals(1, receiver.count("OUTBOX", "PENDING"));
    }
  }

  private static JdbcEventStore store(
      DataSource source, PlatformTransactionManager manager, String owner) {
    return new JdbcEventStore(
        source,
        manager,
        new EventCodec(),
        owner,
        Clock.systemUTC(),
        new EventRetryPolicy(
            3, Duration.ofSeconds(30), Duration.ofMillis(10), Duration.ofSeconds(1)));
  }

  private static RoleResourceGrant grant(String resource) {
    var grant = new RoleResourceGrant();
    grant.setTenantId("tenant-a");
    grant.setRoleId("role");
    grant.setResourceCode(resource);
    return grant;
  }

  private static EventMessage event(String owner) {
    return new EventMessage(
        UUID.randomUUID(),
        "migration.fixture",
        1,
        owner,
        "tenant-a",
        "resource",
        1,
        "operation",
        "trace",
        Instant.now(),
        JsonNodeFactory.instance.objectNode().put("value", "验证"));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
