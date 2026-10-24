package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.rbac.tenant.api.service.MemberLabelDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Label;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Page;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Request;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelException;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelException.Reason;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelProjection.Result;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelStore.ClaimStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rebuilds actual identity pages into independent workflow storage, including crash/fencing paths.
 */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class WorkflowMemberLabelProjectionPostgresTest {
  private static final String STATE = "simpoint_workflow_member_label_state";
  private static final String LABELS = "simpoint_workflow_member_labels";
  private static final Duration LEASE = Duration.ofSeconds(60);

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rebuildsAcrossBoundedRunsAndProcessInstancesWithoutExposingPartialNames(boolean split) {
    try (var fixture = new MemberLabelProjectionFixture(split, 403)) {
      var first = fixture.projection(fixture.directory, 1, 10);
      assertEquals(Result.IN_PROGRESS, first.advance("tenant"));
      assertNull(fixture.store.active("tenant"));
      assertEquals(200L, fixture.rows());
      var restarted = fixture.projection(fixture.directory, 1, 10);
      assertEquals(Result.IN_PROGRESS, restarted.advance("tenant"));
      assertEquals(Result.READY, restarted.advance("tenant"));
      final var active = fixture.store.active("tenant");
      assertEquals(403L, fixture.rows());
      assertEquals(403L, fixture.activeRows());
      assertEquals(fixture.directory.revision(), active.revision());
      assertEquals(Result.READY, first.advance("tenant"));
      assertEquals(active, fixture.store.active("tenant"));
      fixture.identity.jdbc().update("update simpoint_ac_users set name='新名称' where id='u0000'");
      fixture
          .identity
          .jdbc()
          .update("update simpoint_saas_tenant_user_rel set enabled=false where user_id='u0001'");
      assertEquals(Result.IN_PROGRESS, first.advance("tenant"));
      assertEquals(active, fixture.store.active("tenant"));
      assertEquals("成员0000", fixture.activeName("u0000"));
      assertEquals(Result.IN_PROGRESS, restarted.advance("tenant"));
      assertEquals(Result.READY, restarted.advance("tenant"));
      assertNotEquals(active.generation(), fixture.store.active("tenant").generation());
      assertEquals("新名称", fixture.activeName("u0000"));
      assertEquals(402L, fixture.activeRows());
      assertEquals(402L, fixture.rows());
      fixture
          .identity
          .jdbc()
          .update("update simpoint_saas_tenants set deleted_at=now() where id='tenant'");
      assertEquals(Result.READY, first.advance("tenant"));
      assertFalse(fixture.store.active("tenant").tenantActive());
      assertEquals(0L, fixture.rows());
      if (split) {
        assertEquals(
            0,
            fixture
                .workflow
                .jdbc()
                .queryForObject(
                    "select count(*) from pg_tables where schemaname=current_schema() and"
                        + " tablename='simpoint_ac_users'",
                    Integer.class));
        assertEquals(
            0L,
            fixture
                .identity
                .jdbc()
                .queryForObject(
                    "select count(*) from pg_tables where schemaname=current_schema() and"
                        + " tablename=?",
                    Long.class,
                    STATE));
      }
    }
  }

  @Test
  void readSnapshotKeepsTheOldCompleteGenerationDuringAtomicReplacement() throws Exception {
    try (var fixture = new MemberLabelProjectionFixture(true, 3)) {
      var projection = fixture.projection(fixture.directory, 8, 10);
      assertEquals(Result.READY, projection.advance("tenant"));
      try (var reader = fixture.workflow.source().getConnection()) {
        reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        reader.setAutoCommit(false);
        String before =
            scalar(reader, "select active_generation from " + STATE + " where tenant_id='tenant'");
        fixture
            .identity
            .jdbc()
            .update("update simpoint_ac_users set name='changed' where id='u0000'");
        assertEquals(Result.READY, projection.advance("tenant"));
        assertEquals(
            before,
            scalar(reader, "select active_generation from " + STATE + " where tenant_id='tenant'"));
        assertEquals(
            "成员0000",
            scalar(
                reader,
                "select name from "
                    + LABELS
                    + " where tenant_id='tenant' and generation='"
                    + before
                    + "' and user_id='u0000'"));
        reader.commit();
      }
      assertEquals("changed", fixture.activeName("u0000"));
      assertEquals(3L, fixture.rows());
    }
  }

  @Test
  void expiredWorkerCannotAppendActivateAbandonOrReleaseItsSuccessor() {
    try (var fixture = new MemberLabelProjectionFixture(true, 3)) {
      var revision = fixture.directory.revision();
      var first = fixture.store.acquire("tenant", revision, "first", LEASE).build();
      assertEquals(
          ClaimStatus.BUSY, fixture.store.acquire("tenant", revision, "second", LEASE).status());
      fixture.jdbc.update(
          "update " + STATE + " set lease_until=clock_timestamp()-interval '1 second'");
      var second = fixture.store.acquire("tenant", revision, "second", LEASE).build();
      assertEquals(first.generation(), second.generation());
      var page = fixture.directory.page(new Request("tenant", revision, null, 200));
      assertEquals(
          Reason.LEASE_LOST,
          assertThrows(
                  WorkflowMemberLabelException.class,
                  () -> fixture.store.append(first, page, LEASE, 10))
              .reason());
      fixture.store.abandon(first);
      fixture.store.release(first);
      assertEquals(
          "second", fixture.jdbc.queryForObject("select lease_owner from " + STATE, String.class));
      var complete = fixture.store.append(second, page, LEASE, 10);
      assertEquals(
          Reason.LEASE_LOST,
          assertThrows(
                  WorkflowMemberLabelException.class, () -> fixture.store.activate(first, revision))
              .reason());
      fixture.store.activate(complete, revision);
      assertEquals(3L, fixture.activeRows());
      assertEquals(
          Reason.SOURCE_REGRESSION,
          assertThrows(
                  WorkflowMemberLabelException.class,
                  () ->
                      fixture.store.acquire(
                          "tenant",
                          new Revision(revision.epoch(), revision.sequence() - 1),
                          "third",
                          LEASE))
              .reason());
      var nextEpoch = new Revision(UUID.randomUUID().toString(), 0);
      var replacement = fixture.store.acquire("tenant", nextEpoch, "third", LEASE).build();
      assertNotEquals(complete.generation(), replacement.generation());
      fixture.store.activate(
          fixture.store.append(
              replacement,
              new Page("tenant", nextEpoch, false, true, List.of(new Label("new", null)), null),
              LEASE,
              10),
          nextEpoch);
      assertEquals(nextEpoch, fixture.store.active("tenant").revision());
      assertEquals(1L, fixture.activeRows());
    }
  }

  @Test
  void failedBatchRollsBackRowsAndCursorAndCanResumeWithoutDuplicates() {
    try (var fixture = new MemberLabelProjectionFixture(true, 203)) {
      var revision = fixture.directory.revision();
      var build = fixture.store.acquire("tenant", revision, "worker", LEASE).build();
      var first = fixture.directory.page(new Request("tenant", revision, null, 200));
      var committed = fixture.store.append(build, first, LEASE, 10);
      fixture.jdbc.execute(
          "alter table " + LABELS + " add constraint test_failed_batch check (user_id <> 'u0201')");
      var second = fixture.directory.page(new Request("tenant", revision, committed.cursor(), 200));
      assertThrows(
          WorkflowMemberLabelException.class,
          () -> fixture.store.append(committed, second, LEASE, 10));
      assertEquals(200L, fixture.rows());
      assertEquals(
          1, fixture.jdbc.queryForObject("select build_pages from " + STATE, Integer.class));
      assertEquals(
          committed.cursor(),
          fixture.jdbc.queryForObject("select build_cursor from " + STATE, String.class));
      fixture.jdbc.execute("alter table " + LABELS + " drop constraint test_failed_batch");
      fixture.store.release(committed);
      var resumed = fixture.store.acquire("tenant", revision, "new-process", LEASE).build();
      fixture.store.activate(fixture.store.append(resumed, second, LEASE, 10), revision);
      assertEquals(203L, fixture.activeRows());
    }
  }

  @Test
  void completedStagingSurvivesWorkerDeathAndActivatesWithoutReadingAnotherPage() {
    try (var fixture = new MemberLabelProjectionFixture(true, 3)) {
      var revision = fixture.directory.revision();
      var claimed = fixture.store.acquire("tenant", revision, "lost-worker", LEASE).build();
      fixture.store.append(
          claimed, fixture.directory.page(new Request("tenant", revision, null, 200)), LEASE, 10);
      fixture.jdbc.update(
          "update " + STATE + " set lease_until=clock_timestamp()-interval '1 second'");
      var resumed =
          new DirectoryWrapper(fixture.directory) {
            @Override
            public Page page(Request request) {
              throw new AssertionError("A complete generation must not fetch another page");
            }
          };
      assertEquals(Result.READY, fixture.projection(resumed, 8, 10).advance("tenant"));
      assertEquals(claimed.generation(), fixture.store.active("tenant").generation());
      assertEquals(3L, fixture.activeRows());
    }
  }

  @Test
  void transportFailureKeepsCommittedPagesAndReleasesTheLease() {
    try (var fixture = new MemberLabelProjectionFixture(true, 203)) {
      var failure = new IllegalStateException("test source outage");
      var directory =
          new DirectoryWrapper(fixture.directory) {
            @Override
            public Page page(Request request) {
              if (request.afterUserId() != null) {
                throw failure;
              }
              return super.page(request);
            }
          };
      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class,
              () -> fixture.projection(directory, 8, 10).advance("tenant")));
      assertEquals(200L, fixture.rows());
      assertNull(fixture.jdbc.queryForObject("select lease_owner from " + STATE, String.class));
      assertEquals(Result.READY, fixture.projection(fixture.directory, 8, 10).advance("tenant"));
      assertEquals(203L, fixture.activeRows());
    }
  }

  @Test
  void changedSourceDiscardsStagingAndFinalFencePreventsActivatingOldNames() {
    try (var fixture = new MemberLabelProjectionFixture(true, 203)) {
      var initial = fixture.projection(fixture.directory, 8, 10);
      assertEquals(Result.READY, initial.advance("tenant"));
      final var active = fixture.store.active("tenant");
      fixture
          .identity
          .jdbc()
          .update("update simpoint_ac_users set name='first update' where id='u0000'");
      var reads = new AtomicInteger();
      var midPage =
          new DirectoryWrapper(fixture.directory) {
            @Override
            public Page page(Request request) {
              var page = super.page(request);
              if (reads.incrementAndGet() == 1) {
                fixture
                    .identity
                    .jdbc()
                    .update("update simpoint_ac_users set name='mid page' where id='u0000'");
              }
              return page;
            }
          };
      assertEquals(Result.RESTART_REQUIRED, fixture.projection(midPage, 8, 10).advance("tenant"));
      assertEquals(active, fixture.store.active("tenant"));
      assertEquals(203L, fixture.rows());
      var fences = new AtomicInteger();
      var finalFence =
          new DirectoryWrapper(fixture.directory) {
            @Override
            public Revision revision() {
              if (fences.incrementAndGet() == 2) {
                fixture
                    .identity
                    .jdbc()
                    .update("update simpoint_ac_users set name='after last page' where id='u0000'");
              }
              return super.revision();
            }
          };
      assertEquals(
          Result.RESTART_REQUIRED, fixture.projection(finalFence, 8, 10).advance("tenant"));
      assertEquals(active, fixture.store.active("tenant"));
      assertEquals(203L, fixture.rows());
      assertEquals(Result.READY, initial.advance("tenant"));
      assertEquals("after last page", fixture.activeName("u0000"));
    }
  }

  @Test
  void sourceRegressionIsRejectedEvenAfterStagingHasBeenDiscarded() {
    try (var fixture = new MemberLabelProjectionFixture(true, 3)) {
      var ordinary = fixture.projection(fixture.directory, 8, 10);
      assertEquals(Result.READY, ordinary.advance("tenant"));
      final var active = fixture.store.active("tenant");
      fixture.identity.jdbc().update("update simpoint_ac_users set name='next' where id='u0000'");
      final var beforeRestart = fixture.directory.revision();
      var changing =
          new DirectoryWrapper(fixture.directory) {
            @Override
            public Page page(Request request) {
              fixture
                  .identity
                  .jdbc()
                  .update("update simpoint_ac_users set name='newest' where id='u0000'");
              return new Page(request.tenantId(), super.revision(), true, false, List.of(), null);
            }
          };
      assertEquals(Result.RESTART_REQUIRED, fixture.projection(changing, 8, 10).advance("tenant"));
      assertEquals(active, fixture.store.active("tenant"));
      assertNull(
          fixture.jdbc.queryForObject("select build_generation from " + STATE, String.class));
      var older =
          new DirectoryWrapper(fixture.directory) {
            @Override
            public Revision revision() {
              return beforeRestart;
            }

            @Override
            public Page page(Request request) {
              throw new AssertionError("Regressed source must not be read");
            }
          };
      assertEquals(
          Reason.SOURCE_REGRESSION,
          assertThrows(
                  WorkflowMemberLabelException.class,
                  () -> fixture.projection(older, 8, 10).advance("tenant"))
              .reason());
      assertEquals(Result.READY, ordinary.advance("tenant"));
      fixture.identity.jdbc().update("update simpoint_ac_users set name='last' where id='u0000'");
      var current = fixture.directory.revision();
      var calls = new AtomicInteger();
      var finalRegression =
          new DirectoryWrapper(fixture.directory) {
            @Override
            public Revision revision() {
              return calls.incrementAndGet() == 1
                  ? current
                  : new Revision(current.epoch(), current.sequence() - 1);
            }
          };
      assertEquals(
          Reason.SOURCE_REGRESSION,
          assertThrows(
                  WorkflowMemberLabelException.class,
                  () -> fixture.projection(finalRegression, 8, 10).advance("tenant"))
              .reason());
      assertEquals(
          current.sequence(),
          fixture.jdbc.queryForObject("select observed_revision from " + STATE, Long.class));
      assertEquals(Result.READY, ordinary.advance("tenant"));
      assertEquals("last", fixture.activeName("u0000"));
    }
  }

  @Test
  void malformedPagesAndCrossPageDuplicatesCannotReplaceTheActiveGeneration() {
    try (var fixture = new MemberLabelProjectionFixture(true, 203)) {
      assertEquals(Result.READY, fixture.projection(fixture.directory, 8, 10).advance("tenant"));
      final var active = fixture.store.active("tenant");
      fixture.identity.jdbc().update("update simpoint_ac_users set name='new' where id='u0000'");
      var foreign =
          new DirectoryWrapper(fixture.directory) {
            @Override
            public Page page(Request request) {
              var page = super.page(request);
              return new Page(
                  "foreign", page.revision(), false, true, page.labels(), page.nextUserId());
            }
          };
      assertEquals(
          Reason.INVALID_RESPONSE,
          assertThrows(
                  WorkflowMemberLabelException.class,
                  () -> fixture.projection(foreign, 8, 10).advance("tenant"))
              .reason());
      var duplicate =
          new DirectoryWrapper(fixture.directory) {
            @Override
            public Page page(Request request) {
              var page = super.page(request);
              if (request.afterUserId() == null) {
                return page;
              }
              var labels = new ArrayList<>(page.labels());
              labels.set(0, new Label("u0000", "duplicate"));
              return new Page("tenant", page.revision(), false, true, labels, page.nextUserId());
            }
          };
      assertEquals(
          Reason.INVALID_RESPONSE,
          assertThrows(
                  WorkflowMemberLabelException.class,
                  () -> fixture.projection(duplicate, 8, 10).advance("tenant"))
              .reason());
      assertEquals(active, fixture.store.active("tenant"));
      assertEquals(203L, fixture.rows());
    }
  }

  @Test
  void buildLimitsFailExplicitlyAndCallerTransactionsAreRejectedBeforeDirectoryReads() {
    try (var fixture = new MemberLabelProjectionFixture(true, 203)) {
      var bounded = fixture.projection(fixture.directory, 1, 1);
      assertEquals(Result.IN_PROGRESS, bounded.advance("tenant"));
      assertEquals(
          Reason.BUILD_LIMIT,
          assertThrows(WorkflowMemberLabelException.class, () -> bounded.advance("tenant"))
              .reason());
      assertNull(fixture.store.active("tenant"));
      assertEquals(0L, fixture.rows());
      var observed =
          new DirectoryWrapper(fixture.directory) {
            @Override
            public Revision revision() {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return super.revision();
            }

            @Override
            public Page page(Request request) {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return super.page(request);
            }
          };
      var outer = new TransactionTemplate(fixture.workflowTransactions);
      outer.setReadOnly(true);
      outer.executeWithoutResult(
          status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertThrows(
                org.springframework.transaction.IllegalTransactionStateException.class,
                () -> fixture.projection(observed, 8, 10).advance("tenant"));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            status.setRollbackOnly();
          });
      assertNull(fixture.store.active("tenant"));
      assertEquals(Result.READY, fixture.projection(observed, 8, 10).advance("tenant"));
      assertEquals(203L, fixture.activeRows());
    }
  }

  @Test
  void concurrentLocalRunsAreBoundedAndOtherProcessCannotStealLiveBuild() throws Exception {
    try (var fixture = new MemberLabelProjectionFixture(true, 3);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blocked =
          new DirectoryWrapper(fixture.directory) {
            @Override
            public Page page(Request request) {
              entered.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Test worker timeout");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
              }
              return super.page(request);
            }
          };
      var first = fixture.projection(blocked, 8, 10);
      var running =
          workers.submit(
              () -> {
                MemberLabelProjectionFixture.serviceIdentity();
                try {
                  return first.advance("tenant");
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertEquals(Result.BUSY, first.advance("tenant"));
        assertEquals(Result.BUSY, fixture.projection(fixture.directory, 8, 10).advance("tenant"));
      } finally {
        release.countDown();
      }
      assertEquals(Result.READY, running.get(10, TimeUnit.SECONDS));
      assertEquals(3L, fixture.activeRows());
    }
  }

  private static String scalar(Connection connection, String sql) throws Exception {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }

  private static class DirectoryWrapper implements MemberLabelDirectory {
    private final MemberLabelDirectory delegate;

    DirectoryWrapper(MemberLabelDirectory delegate) {
      this.delegate = delegate;
    }

    @Override
    public Revision revision() {
      return delegate.revision();
    }

    @Override
    public Page page(Request request) {
      return delegate.page(request);
    }
  }
}
