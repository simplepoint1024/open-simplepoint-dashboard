package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.plugin.rbac.tenant.api.event.MemberLabelEvents;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;
import org.simplepoint.plugin.rbac.tenant.directory.MemberLabelObservation;
import org.simplepoint.plugin.rbac.tenant.directory.MemberLabelObservationProperties;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelProjection.Result;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelRefresh;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual owner storage, outbox/inbox atomicity, recovery and stale-worker/message fences. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class MemberLabelRefreshPostgresTest {
  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observationAndOutboxRollbackTogetherAndRememberHardDeletedTenants(boolean split) {
    try (var fixture = new MemberLabelEventFixture(split, 3)) {
      fixture
          .labels
          .identity
          .jdbc()
          .update(
              "insert into simpoint_saas_tenants(id,name,owner_id,tenant_type) values"
                  + " ('deleted','Deleted','u0000','ORGANIZATION'),('truncated','Truncated','u0000','ORGANIZATION')");
      fixture.labels.identity.jdbc().update("delete from simpoint_saas_tenants where id='deleted'");
      assertEquals(3L, fixture.observation.pending());
      var publications = new AtomicInteger();
      var failing =
          new MemberLabelObservation(
              fixture.identitySource,
              fixture.identityTransactions,
              event -> {
                fixture.sender.publish(event);
                if (publications.incrementAndGet() == 2) {
                  throw new IllegalStateException("rollback probe");
                }
              },
              new MemberLabelObservationProperties(2, Duration.ofSeconds(2)));
      assertThrows(IllegalStateException.class, failing::observe);
      assertEquals(0, fixture.sender.count("OUTBOX", "PENDING"));
      assertEquals(
          0,
          fixture
              .labels
              .identity
              .jdbc()
              .queryForObject(
                  "select count(*) from simpoint_identity_member_label_observations where"
                      + " observed_at is not null",
                  Integer.class));
      assertEquals(2, fixture.observation.observe());
      assertEquals(1, fixture.observation.observe());
      assertEquals(0, fixture.observation.observe());
      assertEquals(3, fixture.sender.count("OUTBOX", "PENDING"));
      assertEquals(0L, fixture.observation.pending());
      fixture.labels.identity.jdbc().execute("truncate simpoint_saas_tenants cascade");
      assertEquals(2, fixture.observation.observe());
      assertEquals(1, fixture.observation.observe());
      assertEquals(6, fixture.sender.count("OUTBOX", "PENDING"));
      assertEquals(
          3,
          fixture
              .labels
              .identity
              .jdbc()
              .queryForObject(
                  "select count(*) from simpoint_identity_member_label_observations",
                  Integer.class));
      if (split) {
        assertEquals(0, fixture.receiver.count("OUTBOX", "PENDING"));
      }
    }
  }

  @Test
  void partiallyObservedNewRevisionCannotBeForgottenBySourceRollback() {
    try (var fixture = new MemberLabelEventFixture(true, 3)) {
      fixture
          .labels
          .identity
          .jdbc()
          .update(
              "insert into simpoint_saas_tenants(id,name,owner_id,tenant_type) values"
                  + " ('another','Another','u0000','ORGANIZATION'),('third','Third','u0000','ORGANIZATION')");
      assertEquals(2, fixture.observation.observe());
      assertFalse(
          fixture
              .labels
              .identity
              .jdbc()
              .queryForObject(
                  "select notification_complete from simpoint_identity_member_label_revision",
                  Boolean.class));
      fixture
          .labels
          .identity
          .jdbc()
          .update("update simpoint_identity_member_label_revision set revision=revision-1");
      assertThrows(IllegalStateException.class, fixture.observation::observe);
      assertEquals(2, fixture.sender.count("OUTBOX", "PENDING"));
    }
  }

  @Test
  void inboxCompletionAndRefreshIntentCommitTogetherWithoutAnyDirectoryRpc() {
    try (var fixture = new MemberLabelEventFixture(true, 3)) {
      fixture.labels.store.track("tenant");
      assertEquals(1, fixture.refresh(8).runBatch());
      final var old = fixture.labels.store.active("tenant");
      fixture
          .labels
          .identity
          .jdbc()
          .update("update simpoint_ac_users set name='事件新名称' where id='u0000'");
      assertEquals(1, fixture.observation.observe());
      var event = fixture.sender.claim("OUTBOX").event();
      assertThrows(IllegalTransactionStateException.class, () -> fixture.handler.handle(event));
      fixture.receiver.receive(MemberLabelEvents.WORKFLOW_SUBSCRIPTION, event);
      fixture.receiver.receive(MemberLabelEvents.WORKFLOW_SUBSCRIPTION, event);
      assertEquals(1, fixture.receiver.count("INBOX", "PENDING"));
      var job = fixture.receiver.claim("INBOX");
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.receiver.process(
                  job,
                  () -> {
                    fixture.handler.handle(event);
                    throw new IllegalStateException("rollback probe");
                  }));
      assertEquals(0L, generation(fixture));
      assertNull(fixture.queue.claim());
      fixture.receiver.process(job, () -> fixture.handler.handle(event));
      assertEquals(1, fixture.receiver.count("INBOX", "DONE"));
      assertEquals(1L, generation(fixture));
      assertEquals(old, fixture.labels.store.active("tenant"));
      assertEquals(1, fixture.refresh(8).runBatch());
      assertEquals("事件新名称", fixture.labels.activeName("u0000"));
    }
  }

  @Test
  void staleMessagesCannotDowngradeTheProjectionAndNewIntentSurvivesAnOlderCompletion() {
    try (var fixture = new MemberLabelEventFixture(true, 3)) {
      fixture.labels.store.track("tenant");
      fixture.refresh(8).runBatch();
      final var old = fixture.labels.directory.revision();
      var stale = MemberLabelEvents.observed("tenant", old, Instant.now());
      effect(fixture, stale);
      assertEquals(0L, generation(fixture));
      fixture
          .labels
          .identity
          .jdbc()
          .update("update simpoint_ac_users set name='更新' where id='u0000'");
      var current =
          MemberLabelEvents.observed("tenant", fixture.labels.directory.revision(), Instant.now());
      effect(fixture, current);
      var running = fixture.queue.claim();
      assertEquals(1L, running.generation());
      effect(fixture, current);
      fixture.queue.finish(running, Result.READY);
      assertEquals(1, fixture.refresh(8).runBatch());
      assertEquals("更新", fixture.labels.activeName("u0000"));
      effect(fixture, stale);
      assertNull(fixture.queue.claim());
      // Epochs are incomparable: a foreign hint schedules a source check but never changes source
      // state.
      var active = fixture.labels.store.active("tenant");
      effect(
          fixture,
          MemberLabelEvents.observed(
              "tenant", new Revision(java.util.UUID.randomUUID().toString(), 999), Instant.now()));
      assertEquals(active, fixture.labels.store.active("tenant"));
      fixture.refresh(8).runBatch();
      assertEquals(active, fixture.labels.store.active("tenant"));
      effect(fixture, MemberLabelEvents.observed("untracked", old, Instant.now()));
      assertNull(fixture.labels.store.active("untracked"));
      assertEquals(
          1,
          fixture.labels.jdbc.queryForObject(
              "select count(*) from simpoint_workflow_member_label_state", Integer.class));
    }
  }

  @Test
  void expiredRefreshOwnerCannotReleaseOrDelayItsSuccessor() {
    try (var fixture = new MemberLabelEventFixture(true, 3)) {
      fixture.labels.store.track("tenant");
      var old = fixture.queue.claim();
      assertNull(fixture.queue.claim());
      fixture.labels.jdbc.update(
          "update simpoint_workflow_member_label_refresh set lease_until=clock_timestamp()-interval"
              + " '1 second'");
      var current = fixture.queue.claim();
      assertNotEquals(old.token(), current.token());
      fixture.queue.finish(old, Result.READY);
      fixture.queue.failed(old, new IllegalStateException("old failure"));
      assertEquals(
          current.token(),
          fixture.labels.jdbc.queryForObject(
              "select lease_token from simpoint_workflow_member_label_refresh", String.class));
      assertEquals(
          0,
          fixture.labels.jdbc.queryForObject(
              "select failures from simpoint_workflow_member_label_refresh", Integer.class));
      fixture.queue.finish(current, Result.READY);
      assertNull(fixture.queue.claim());
    }
  }

  @Test
  void incompleteBuildResumesWithoutAnotherBrowserRequestAndReconcilesWithoutMessages() {
    try (var fixture = new MemberLabelEventFixture(true, 203)) {
      fixture.labels.store.track("tenant");
      assertEquals(1, fixture.refresh(1).runBatch());
      assertNull(fixture.labels.store.active("tenant"));
      assertEquals(200L, fixture.labels.rows());
      fixture.due();
      // A new coordinator continues the durable cursor left by the previous process.
      assertEquals(1, fixture.refresh(1).runBatch());
      assertEquals(203L, fixture.labels.activeRows());
      fixture
          .labels
          .identity
          .jdbc()
          .update("update simpoint_ac_users set name='对账更新' where id='u0000'");
      fixture.due();
      fixture.refresh(8).runBatch();
      assertEquals("对账更新", fixture.labels.activeName("u0000"));
      assertEquals(0, fixture.receiver.count("INBOX", "DONE"));
    }
  }

  @Test
  void failuresPersistBoundedBackoffAndRecoverAfterSourceReturns() {
    try (var fixture = new MemberLabelEventFixture(true, 3)) {
      fixture.labels.store.track("tenant");
      var unavailable =
          new org.simplepoint.plugin.rbac.tenant.api.service.MemberLabelDirectory() {
            public Revision revision() {
              throw new IllegalStateException("private source details");
            }

            public org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Page page(
                org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Request
                    request) {
              throw new AssertionError("No pages when the source revision is unavailable");
            }
          };
      var worker =
          new WorkflowMemberLabelRefresh(
              fixture.queue,
              fixture.labels.projection(unavailable, 8, 10),
              fixture.refreshProperties);
      assertEquals(1, worker.runBatch());
      assertEquals(
          1L,
          fixture.queue.count(
              org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelRefreshQueue.State
                  .FAILED));
      assertNull(fixture.labels.store.active("tenant"));
      assertNull(fixture.queue.claim());
      assertEquals(
          "UNAVAILABLE",
          fixture.labels.jdbc.queryForObject(
              "select last_error from simpoint_workflow_member_label_refresh", String.class));
      assertTrue(
          fixture.labels.jdbc.queryForObject(
              "select due_at>clock_timestamp() from simpoint_workflow_member_label_refresh",
              Boolean.class));
      fixture.due();
      assertEquals(1, fixture.refresh(8).runBatch());
      assertEquals("成员0000", fixture.labels.activeName("u0000"));
      assertNull(
          fixture.labels.jdbc.queryForObject(
              "select last_error from simpoint_workflow_member_label_refresh", String.class));
      assertFalse(
          fixture.labels.jdbc.queryForObject(
              "select checked_at is null from simpoint_workflow_member_label_refresh",
              Boolean.class));
    }
  }

  @Test
  void selectedIdentityConfigurationUsesOwnedStorageAndStopsItsWorker() throws Exception {
    try (var fixture = new MemberLabelEventFixture(true, 3)) {
      org.simplepoint.core.scheduling.PeriodicWorker worker;
      try (var context =
          new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(
                new org.springframework.core.env.MapPropertySource(
                    "owners",
                    java.util.Map.of(
                        "simplepoint.events.enabled", true,
                        "simplepoint.persistence.modules.identity", "identity",
                        "simplepoint.persistence.sources.identity", "identitySource",
                        "simplepoint.persistence.modules.workflow", "workflow",
                        "simplepoint.persistence.sources.workflow", "workflowSource")));
        context.registerBean(
            "identitySource", javax.sql.DataSource.class, () -> fixture.labels.identity.source());
        context.registerBean(
            "workflowSource",
            javax.sql.DataSource.class,
            () -> fixture.labels.workflow.source(),
            definition -> definition.setPrimary(true));
        context.registerBean(
            org.simplepoint.platform.composition.ModulePlan.class,
            () ->
                fixture.labels.context.getBean(
                    org.simplepoint.platform.composition.ModulePlan.class));
        context.registerBean(org.simplepoint.events.api.EventPublisher.class, () -> fixture.sender);
        context.register(
            IdentityLabelsStoragePostgresTest.Persistence.class,
            org.simplepoint.plugin.rbac.module.IdentityLabelObservationConfiguration.class);
        context.refresh();
        worker = context.getBean(org.simplepoint.core.scheduling.PeriodicWorker.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (fixture.sender.count("OUTBOX", "PENDING") == 0 && System.nanoTime() < deadline) {
          Thread.sleep(25);
        }
        assertEquals(1, fixture.sender.count("OUTBOX", "PENDING"));
        assertEquals(0, fixture.receiver.count("OUTBOX", "PENDING"));
        assertTrue(worker.isRunning());
      }
      assertFalse(worker.isRunning());
      // A host reusing only the identity implementation must not accidentally start this producer.
      var scanner =
          new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(
              true);
      assertTrue(
          scanner
              .findCandidateComponents("org.simplepoint.plugin.rbac.tenant.directory")
              .isEmpty());
    }
  }

  private static long generation(MemberLabelEventFixture fixture) {
    return fixture.labels.jdbc.queryForObject(
        "select generation from simpoint_workflow_member_label_refresh where tenant_id='tenant'",
        Long.class);
  }

  private static void effect(MemberLabelEventFixture fixture, EventMessage event) {
    new TransactionTemplate(fixture.labels.workflowTransactions)
        .executeWithoutResult(status -> fixture.handler.handle(event));
  }
}
