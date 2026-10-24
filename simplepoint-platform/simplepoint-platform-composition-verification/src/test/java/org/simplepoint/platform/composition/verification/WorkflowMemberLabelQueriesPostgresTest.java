package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.service.MemberLabelDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Page;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Request;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowMatter;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelException;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelException.Reason;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelProjection.Result;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelReader;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelReads;
import org.simplepoint.plugin.workflow.service.WorkflowInboxQueries;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real domain queries must retain SQL semantics without any identity table in workflow storage. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class WorkflowMemberLabelQueriesPostgresTest {
  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void filtersSortsCountsAndPagesCurrentLabelsInSharedAndSplitStorage(boolean split) {
    try (var fixture = new WorkflowQueryFixture(split)) {
      fixture.start("u0002", "A");
      fixture.start("u0001", "B");
      fixture.start("u0002", "C");
      var source = new OutsideTransactionDirectory(fixture.labels.directory);
      var matters = fixture.matters(source);
      final var inbox = fixture.inbox(source);
      var filter = Map.of("starterName", "equals:成员0002");
      var second = PageRequest.of(1, 1, Sort.by("title"));
      var page = matters.query("all", filter, second);
      assertEquals(2, page.totalElements());
      assertEquals(List.of("C"), page.content().stream().map(WorkflowMatter::title).toList());
      var tasks = inbox.query("pending", filter, second);
      assertEquals(2, tasks.totalElements());
      assertEquals("C", tasks.content().getFirst().title());
      assertEquals("成员0000", tasks.content().getFirst().assigneeName());
      assertEquals(
          "B",
          matters
              .query("all", Map.of(), PageRequest.of(0, 1, Sort.by("starterName")))
              .content()
              .getFirst()
              .title());
      fixture
          .labels
          .identity
          .jdbc()
          .update("update simpoint_ac_users set name='新名称🦊' where id='u0002'");
      assertEquals(0, matters.query("all", filter, second).totalElements());
      assertEquals(
          2, inbox.query("pending", Map.of("starterName", "equals:新名称🦊"), second).totalElements());
      fixture
          .labels
          .identity
          .jdbc()
          .update("update simpoint_saas_tenant_user_rel set enabled=false where user_id='u0002'");
      assertEquals(
          0, matters.query("all", Map.of("starterName", "equals:新名称🦊"), second).totalElements());
      var remaining = matters.query("all", Map.of(), PageRequest.of(0, 10, Sort.by("title")));
      assertEquals(3, remaining.totalElements());
      assertNull(remaining.content().getFirst().starterName());
      assertEquals("成员0001", remaining.content().get(1).starterName());
      if (split) {
        assertEquals(
            0,
            fixture.labels.jdbc.queryForObject(
                """
                select count(*) from pg_tables where schemaname=current_schema()
                and (tablename like 'simpoint_ac_%' or tablename like 'simpoint_saas_%')
                """,
                Integer.class));
      }
    }
  }

  @Test
  void projectionNeverReplacesTenantPersonalOrTaskAuthorization() {
    try (var fixture = new WorkflowQueryFixture(true)) {
      final var instance = fixture.start("u0001", "审批");
      var source = new OutsideTransactionDirectory(fixture.labels.directory);
      var matters = fixture.matters(source);
      final var inbox = fixture.inbox(source);
      WorkflowQueryFixture.authorize("u0000", "tenant", false);
      assertThrows(
          WorkflowException.class, () -> matters.query("all", Map.of(), PageRequest.of(0, 10)));
      assertEquals(0, source.calls.get());
      WorkflowQueryFixture.authorize("u0000", "tenant", true);
      assertEquals(0, matters.query("started", Map.of(), PageRequest.of(0, 10)).totalElements());
      fixture.labels.jdbc.update(
          """
          insert into simpoint_workflow_instance_watchers
          (id,tenant_id,process_instance_id,user_id,created_at) values ('watch','tenant',?,'u0000',now())
          """,
          instance);
      assertEquals(1, matters.query("copied", Map.of(), PageRequest.of(0, 10)).totalElements());
      String task =
          inbox.query("pending", Map.of(), PageRequest.of(0, 10)).content().getFirst().taskId();
      assertEquals(task, inbox.task(task).taskId());
      WorkflowQueryFixture.authorize("u0001", "tenant", true);
      assertThrows(WorkflowException.class, () -> inbox.task(task));
      WorkflowQueryFixture.authorize("u0000", "another-tenant", true);
      assertEquals(0, matters.query("all", Map.of(), PageRequest.of(0, 10)).totalElements());
      assertThrows(WorkflowException.class, () -> inbox.task(task));
    }
  }

  @Test
  void retriedDomainPageUsesNewNamesAndNewEngineFactsTogether() {
    try (var fixture = new WorkflowQueryFixture(true)) {
      fixture.start("u0001", "A");
      fixture.start("u0001", "B");
      var source = new OutsideTransactionDirectory(fixture.labels.directory);
      var reader = fixture.reader(source);
      var attempts = new AtomicInteger();
      var totals = new ArrayList<Long>();
      var decorated =
          new WorkflowMemberLabelReader() {
            @Override
            public <T> T read(String tenant, Supplier<T> query) {
              return reader.read(
                  tenant,
                  () -> {
                    T value = query.get();
                    var page =
                        (org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult<?>)
                            value;
                    totals.add(page.totalElements());
                    if (attempts.getAndIncrement() == 0) {
                      CompletableFuture.runAsync(
                              () -> {
                                fixture
                                    .labels
                                    .identity
                                    .jdbc()
                                    .update(
                                        "update simpoint_ac_users set name='新版名称' where"
                                            + " id='u0001'");
                                var task =
                                    fixture
                                        .engine
                                        .getTaskService()
                                        .createTaskQuery()
                                        .listPage(0, 1)
                                        .getFirst();
                                fixture.engine.getTaskService().complete(task.getId());
                              })
                          .join();
                    }
                    return value;
                  });
            }
          };
      var page =
          new WorkflowInboxQueries(fixture.em, decorated)
              .query("pending", Map.of(), PageRequest.of(0, 10));
      assertEquals(List.of(2L, 1L), totals);
      assertEquals(1, page.totalElements());
      assertEquals(1, page.content().size());
      assertEquals("新版名称", page.content().getFirst().starterName());
    }
  }

  @Test
  void localSnapshotStaysCoherentAndChangedSourceDiscardsAndRetriesTheWholeQuery() {
    try (var fixture = new MemberLabelProjectionFixture(true, 3)) {
      var source = new OutsideTransactionDirectory(fixture.directory);
      var reader =
          new WorkflowMemberLabelReads(fixture.store, fixture.projection(source, 8, 10), source);
      var calls = new AtomicInteger();
      String result =
          reader.read(
              "tenant",
              () -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(
                    "repeatable read",
                    fixture.jdbc.queryForObject("show transaction_isolation", String.class));
                String first = fixture.activeName("u0000");
                if (calls.getAndIncrement() == 0) {
                  fixture
                      .identity
                      .jdbc()
                      .update("update simpoint_ac_users set name='变更后' where id='u0000'");
                }
                assertEquals(first, fixture.activeName("u0000"));
                return first;
              });
      assertEquals("变更后", result);
      assertEquals(2, calls.get());
    }
  }

  @Test
  void unavailableSourceNeverReturnsStaleNamesAndCallerTransactionsNeverReachRpc() {
    try (var fixture = new MemberLabelProjectionFixture(true, 3)) {
      assertEquals(Result.READY, fixture.projection(fixture.directory, 8, 10).advance("tenant"));
      var calls = new AtomicInteger();
      var source =
          new OutsideTransactionDirectory(fixture.directory) {
            @Override
            public Revision revision() {
              calls.incrementAndGet();
              throw new IllegalStateException("source unavailable");
            }
          };
      var reader =
          new WorkflowMemberLabelReads(fixture.store, fixture.projection(source, 8, 10), source);
      var transaction = new TransactionTemplate(fixture.workflowTransactions);
      assertThrows(
          IllegalTransactionStateException.class,
          () -> transaction.execute(status -> reader.read("tenant", () -> "stale")));
      assertEquals(0, calls.get());
      assertEquals(
          Reason.SOURCE_UNAVAILABLE,
          assertThrows(
                  WorkflowMemberLabelException.class, () -> reader.read("tenant", () -> "stale"))
              .reason());
      assertEquals("成员0000", fixture.activeName("u0000"));
    }
  }

  @Test
  void unfinishedBuildDoesNotExecuteQueriesAndResumesOnNextRequest() {
    try (var fixture = new MemberLabelProjectionFixture(true, 203)) {
      var source = new OutsideTransactionDirectory(fixture.directory);
      var reader =
          new WorkflowMemberLabelReads(fixture.store, fixture.projection(source, 1, 10), source);
      var queries = new AtomicInteger();
      assertEquals(
          Reason.NOT_READY,
          assertThrows(
                  WorkflowMemberLabelException.class,
                  () -> reader.read("tenant", queries::incrementAndGet))
              .reason());
      assertEquals(0, queries.get());
      assertNull(fixture.store.active("tenant"));
      assertEquals(203L, reader.read("tenant", fixture::activeRows));
    }
  }

  @Test
  void observedPostQueryRevisionSurvivesFailureAndRejectsRollback() {
    try (var fixture = new MemberLabelProjectionFixture(true, 3)) {
      assertEquals(Result.READY, fixture.projection(fixture.directory, 8, 10).advance("tenant"));
      final var old = fixture.directory.revision();
      var reads = new AtomicInteger();
      var source =
          new OutsideTransactionDirectory(fixture.directory) {
            @Override
            public Revision revision() {
              int read = reads.getAndIncrement();
              return read == 1 ? new Revision(old.epoch(), old.sequence() + 1) : old;
            }
          };
      var reader =
          new WorkflowMemberLabelReads(fixture.store, fixture.projection(source, 8, 10), source);
      assertEquals(
          Reason.SOURCE_REGRESSION,
          assertThrows(
                  WorkflowMemberLabelException.class,
                  () -> reader.read("tenant", fixture::activeRows))
              .reason());
      var restarted =
          new WorkflowMemberLabelReads(
              fixture.store, fixture.projection(fixture.directory, 8, 10), fixture.directory);
      assertEquals(
          Reason.SOURCE_REGRESSION,
          assertThrows(
                  WorkflowMemberLabelException.class,
                  () -> restarted.read("tenant", fixture::activeRows))
              .reason());
    }
  }

  private static class OutsideTransactionDirectory implements MemberLabelDirectory {
    private final MemberLabelDirectory delegate;
    final AtomicInteger calls = new AtomicInteger();

    OutsideTransactionDirectory(MemberLabelDirectory delegate) {
      this.delegate = delegate;
    }

    @Override
    public Revision revision() {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      calls.incrementAndGet();
      return delegate.revision();
    }

    @Override
    public Page page(Request request) {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      return delegate.page(request);
    }
  }
}
