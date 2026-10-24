package org.simplepoint.platform.installation.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationException;
import org.simplepoint.platform.installation.InstallationException.Code;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationJournal;
import org.simplepoint.platform.installation.InstallationPlan;
import org.simplepoint.platform.installation.InstallationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class JdbcInstallationJournalTest {
  private DriverManagerDataSource source;
  private JdbcTemplate sql;
  private JdbcInstallationJournal journal;
  private String schema;
  private java.sql.Connection schemaConnection;

  @BeforeEach
  void open() throws Exception {
    String url = System.getenv("SP_INSTALLATION_TEST_POSTGRES_URL");
    if (url == null) {
      source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:install-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      // H2 2.4 CHECK IN expressions retain their schema-creation session comparator.
      schemaConnection = source.getConnection();
      new ResourceDatabasePopulator(
              new ClassPathResource(
                  "META-INF/simplepoint/modules/installation-journal/db/postgresql/V1__installation_journal.sql"))
          .populate(schemaConnection);
    } else {
      if (!url.equals("jdbc:postgresql://127.0.0.1:45436/installation_test")) {
        throw new IllegalArgumentException("Only the disposable installation database is allowed");
      }
      var base =
          new DriverManagerDataSource(
              url, "resource_test", System.getenv("SP_INSTALLATION_TEST_POSTGRES_PASSWORD"));
      schema = "installation_" + UUID.randomUUID().toString().replace("-", "");
      new JdbcTemplate(base).execute("create schema " + schema);
      source =
          new DriverManagerDataSource(
              url + "?currentSchema=" + schema, base.getUsername(), base.getPassword());
      var migrations = InstallationJournalMigrations.plan("journal");
      new ModuleMigrationEngine().migrate(migrations, Map.of("journal", source));
      new ModuleMigrationEngine().verify(migrations, Map.of("journal", source));
    }
    sql = new JdbcTemplate(source);
    sql.setQueryTimeout(3);
    journal = new JdbcInstallationJournal(source);
  }

  @AfterEach
  void close() throws Exception {
    if (sql != null) {
      sql.execute(schema == null ? "shutdown" : "drop schema " + schema + " cascade");
    }
    if (schemaConnection != null) {
      schemaConnection.close();
    }
  }

  @Test
  void completedOperationsSurviveNewRunnerWithoutRepeatingActions() {
    var first = action("first");
    var second = action("second");
    var plan = plan(first, second);
    assertTrue(run(plan, first, second).complete());
    assertEquals(1, first.calls.get());
    assertEquals(1, second.calls.get());
    assertTrue(
        new InstallationRunner(new JdbcInstallationJournal(source))
            .run(plan, List.of(first, second))
            .complete());
    assertEquals(2L, count("simpoint_installation_attempts"));
    assertEquals(1, first.calls.get());
    assertEquals(1, second.calls.get());
  }

  @Test
  void lostJournalAcknowledgementReconcilesDurableOwnerReceipt() {
    var action = action("first");
    var plan = plan(action);
    journal.open(plan);
    final var attempt = journal.claim(plan, 0);
    final String receipt = action.apply();
    assertFalse(journal.read(plan).complete());
    assertTrue(run(plan, action).complete());
    assertEquals(1, action.calls.get());
    journal.complete(plan, attempt, receipt);
    assertCode(Code.STEP_CONFLICT, () -> journal.complete(plan, attempt, hash("different")));
  }

  @Test
  void unavailableOwnerEvidenceKeepsTheExistingAttemptUnchanged() {
    var action = action("first");
    var plan = plan(action);
    journal.open(plan);
    final var attempt = journal.claim(plan, 0);
    action.failLookup = true;
    assertCode(Code.RECONCILIATION_FAILED, () -> run(plan, action));
    assertEquals(attempt.token(), journal.read(plan).steps().getFirst().attempt());
    assertEquals(InstallationJournal.State.RUNNING, journal.read(plan).steps().getFirst().state());
    assertEquals(0, action.calls.get());
    action.failLookup = false;
    action.receipt = hash("owner-completed");
    assertTrue(run(plan, action).complete());
    assertEquals(0, action.calls.get());
  }

  @Test
  void failedActionUsesDurableRetryAndNeverStoresProviderDiagnosticText() {
    var action = action("first");
    action.failBefore = true;
    var plan = plan(action);
    assertCode(Code.ACTION_FAILED, () -> run(plan, action));
    assertEquals(InstallationJournal.State.FAILED, journal.read(plan).steps().getFirst().state());
    assertEquals(
        "ACTION_FAILED",
        sql.queryForObject("select error_code from simpoint_installation_attempts", String.class));
    action.failBefore = false;
    assertTrue(run(plan, action).complete());
    assertEquals(2, action.calls.get());
    assertEquals(2L, count("simpoint_installation_attempts"));
  }

  @Test
  void actionCommitThenTransportFailureDoesNotRepeatItsEffect() {
    var action = action("first");
    action.failAfter = true;
    var plan = plan(action);
    assertCode(Code.ACTION_FAILED, () -> run(plan, action));
    assertTrue(run(plan, action).complete());
    assertEquals(1, action.calls.get());
  }

  @Test
  void runningAttemptNeedsEvidenceAndLateAcknowledgementIsFenced() {
    var action = action("first");
    var plan = plan(action);
    journal.open(plan);
    var previous = journal.claim(plan, 0);
    assertCode(Code.RECOVERY_REQUIRED, () -> run(plan, action));
    assertEquals(0, action.calls.get());
    journal.authorizeRetry(plan, previous, hash("verified-old-executor-retirement"));
    assertCode(Code.STEP_CONFLICT, () -> journal.complete(plan, previous, hash("late")));
    assertTrue(run(plan, action).complete());
    assertEquals(
        hash("verified-old-executor-retirement"),
        sql.queryForObject(
            "select retirement_evidence from simpoint_installation_attempts where attempt_id=?",
            String.class,
            previous.token().toString()));
    assertEquals(2L, count("simpoint_installation_attempts"));
  }

  @Test
  void nonIdempotentFailureRequiresOwnerCompletionRatherThanBlindRetry() {
    var action =
        new OwnedAction(
            new InstallationPlan.Step(
                "first", "fixture", 1, hash("first"), InstallationPlan.Retry.RECONCILE_ONLY));
    action.failBefore = true;
    var plan = plan(action);
    assertCode(Code.ACTION_FAILED, () -> run(plan, action));
    assertCode(Code.RECOVERY_REQUIRED, () -> run(plan, action));
    assertEquals(1, action.calls.get());
    action.receipt = hash("external-owner-verified-completion");
    assertTrue(run(plan, action).complete());
    assertEquals(1, action.calls.get());
  }

  @Test
  void plansGenerationsAndStepOrderCannotDriftOnResume() {
    var first = action("first");
    var second = action("second");
    var plan = plan(first, second);
    journal.open(plan);
    assertCode(Code.STEP_CONFLICT, () -> journal.claim(plan, 1));
    assertCode(Code.STEP_CONFLICT, () -> journal.finish(plan));
    var changed =
        new InstallationPlan(
            plan.operationId(), plan.target(), 0, hash("changed-release"), plan.steps());
    assertCode(Code.PLAN_CONFLICT, () -> journal.open(changed));
    var overlap =
        new InstallationPlan(
            UUID.randomUUID(), plan.target(), 1, plan.releaseFingerprint(), plan.steps());
    assertCode(Code.OPERATION_IN_PROGRESS, () -> journal.open(overlap));
    run(plan, first, second);
    journal.open(overlap);
    assertCode(Code.GENERATION_CONFLICT, () -> journal.read(plan));
    assertEquals(2L, count("simpoint_installation_operations"));
  }

  @Test
  void wrongAdapterFailsBeforeJournalOrDomainWrites() {
    var action = action("first");
    var plan = plan(action);
    assertCode(Code.ADAPTER_MISMATCH, () -> run(plan, action("other")));
    assertEquals(0L, count("simpoint_installation_targets"));
    assertEquals(0, action.calls.get());
  }

  @Test
  void parallelRunnersCannotBothClaimOneAttempt() throws Exception {
    var plan = plan(action("first"));
    journal.open(plan);
    var start = new CountDownLatch(1);
    var acquired = new AtomicInteger();
    var rejected = new AtomicInteger();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 2; i++) {
        tasks.add(
            workers.submit(
                () -> {
                  await(start);
                  try {
                    journal.claim(plan, 0);
                    acquired.incrementAndGet();
                  } catch (InstallationException failure) {
                    assertEquals(Code.RECOVERY_REQUIRED, failure.code());
                    rejected.incrementAndGet();
                  }
                }));
      }
      start.countDown();
      for (var task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }
    }
    assertEquals(1, acquired.get());
    assertEquals(1, rejected.get());
    assertEquals(1L, count("simpoint_installation_attempts"));
  }

  @Test
  void journalCommitFailureDoesNotConvertSuccessfulBusinessActionToFailure() {
    var action = action("first");
    var plan = plan(action);
    sql.execute(
        "alter table simpoint_installation_steps add constraint reject_complete check (state <>"
            + " 'COMPLETE')");
    assertCode(Code.JOURNAL_UNAVAILABLE, () -> run(plan, action));
    assertEquals(InstallationJournal.State.RUNNING, journal.read(plan).steps().getFirst().state());
    sql.execute("alter table simpoint_installation_steps drop constraint reject_complete");
    assertTrue(run(plan, action).complete());
    assertEquals(1, action.calls.get());
  }

  @Test
  void openingAnInvalidBatchRollsBackTargetAndAllPlanRows() {
    var first = action("first");
    var second = action("second");
    var plan = plan(first, second);
    sql.execute(
        "alter table simpoint_installation_steps add constraint reject_second check (step_id <>"
            + " 'second')");
    assertCode(Code.JOURNAL_UNAVAILABLE, () -> journal.open(plan));
    assertEquals(0L, count("simpoint_installation_targets"));
    assertEquals(0L, count("simpoint_installation_operations"));
    assertEquals(0L, count("simpoint_installation_steps"));
    sql.execute("alter table simpoint_installation_steps drop constraint reject_second");
    assertTrue(run(plan, first, second).complete());
  }

  @Test
  void domainActionDoesNotHoldTheJournalTransaction() {
    var action = action("first");
    var plan = plan(action);
    action.duringApply =
        () ->
            assertEquals(
                1,
                sql.update(
                    "update simpoint_installation_targets set generation=generation where"
                        + " target_id=?",
                    plan.target()));
    assertTrue(run(plan, action).complete());
  }

  private InstallationJournal.Snapshot run(InstallationPlan plan, OwnedAction... actions) {
    return new InstallationRunner(journal).run(plan, List.of(actions));
  }

  private InstallationPlan plan(OwnedAction... actions) {
    return new InstallationPlan(
        UUID.randomUUID(),
        "fixture",
        0,
        hash("release"),
        java.util.Arrays.stream(actions).map(OwnedAction::definition).toList());
  }

  private OwnedAction action(String name) {
    return new OwnedAction(
        new InstallationPlan.Step(
            name, "fixture", 1, hash(name), InstallationPlan.Retry.DURABLE_IDEMPOTENT));
  }

  private long count(String table) {
    return sql.queryForObject("select count(*) from " + table, Long.class);
  }

  private static String hash(String value) {
    return InstallationFingerprint.of(value);
  }

  private static void assertCode(Code code, org.junit.jupiter.api.function.Executable work) {
    assertEquals(code, assertThrows(InstallationException.class, work).code());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for concurrent journal claim");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static final class OwnedAction implements InstallationAction {
    private final InstallationPlan.Step definition;
    private final AtomicInteger calls = new AtomicInteger();
    private String receipt;
    private boolean failLookup;
    private boolean failBefore;
    private boolean failAfter;
    private Runnable duringApply = () -> {};

    private OwnedAction(InstallationPlan.Step definition) {
      this.definition = definition;
    }

    @Override
    public InstallationPlan.Step definition() {
      return definition;
    }

    @Override
    public String apply() {
      calls.incrementAndGet();
      if (failBefore) {
        throw new IllegalStateException("secret-marker-must-not-enter-journal");
      }
      duringApply.run();
      receipt = hash("owner-" + definition.id());
      if (failAfter) {
        throw new IllegalStateException("response-lost");
      }
      return receipt;
    }

    @Override
    public Optional<String> completedReceipt() {
      if (failLookup) {
        throw new IllegalStateException("sensitive-provider-diagnostic");
      }
      return Optional.ofNullable(receipt);
    }
  }
}
