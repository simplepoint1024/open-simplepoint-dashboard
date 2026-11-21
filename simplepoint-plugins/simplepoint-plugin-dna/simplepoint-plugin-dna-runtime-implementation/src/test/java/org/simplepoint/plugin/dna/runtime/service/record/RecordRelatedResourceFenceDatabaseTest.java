package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.LockModeType;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalResourceProjectionEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.relation.PhysicalRelationCatalog;
import org.simplepoint.plugin.dna.runtime.service.relation.PhysicalRelationCatalogBoundary;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalReferenceResolver;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.security.access.AccessDeniedException;

/** Related resource offline must wait for the already admitted physical transaction to commit. */
class RecordRelatedResourceFenceDatabaseTest {
  @Test
  void targetOfflineWaitsForPhysicalCommitAndOldGenerationCannotResumeAfterReonline()
      throws Exception {
    RecordPublicationPatchDatabaseTest.verify(
        "POSTGRES",
        true,
        db ->
            RecordMaintenanceControlDatabaseTest.withControl(
                db,
                control -> {
                  var em = control.em();
                  var transactions = control.transactions();
                  var repositories = new JpaRepositoryFactory(em);
                  var bindings = repositories.getRepository(PhysicalModelBindingRepository.class);
                  var versions = repositories.getRepository(PhysicalModelVersionRepository.class);
                  var models = repositories.getRepository(PhysicalModelRepository.class);
                  var catalog =
                      new PhysicalRelationCatalog(
                          em,
                          new PhysicalRelationCatalogBoundary(em),
                          versions,
                          bindings,
                          db.fixture().codec,
                          new PhysicalReferenceResolver(
                              models, bindings, versions, db.fixture().codec));
                  var fence =
                      new RecordResourceFence(em, transactions.getTransactionManager(), catalog);
                  var parent = db.fixture().parent;
                  // Two authorized entrances to the same root model exercise independent resource
                  // lifecycles.
                  // Record-to-record self references still require different record IDs at the
                  // write boundary.
                  transactions.executeWithoutResult(
                      status -> {
                        em.persist(projection("source", "z-source", parent.physicalModelId(), 2));
                        em.persist(projection("target", "a-target", parent.physicalModelId(), 7));
                      });
                  var source =
                      new RecordResourceGuard(parent.physicalModelId(), "z-source", 2, true);
                  var target =
                      new RecordResourceGuard(parent.physicalModelId(), "a-target", 7, true);
                  var scopes =
                      List.of(
                          new PhysicalRelationCatalog.Scope(
                              parent.activeVersion(), parent.activeSchema(), parent.target()));
                  var entered = new CountDownLatch(1);
                  var release = new CountDownLatch(1);
                  var offlinePid = new AtomicInteger();
                  try (var workers = Executors.newFixedThreadPool(2)) {
                    var writer =
                        workers.submit(
                            () ->
                                fence.execute(
                                    "tenant-a",
                                    List.of(source, target),
                                    scopes,
                                    () -> {
                                      try (var connection =
                                          RecordPublicationPatchDatabaseTest.transaction(db)) {
                                        for (var row : db.fixture().seed())
                                          assertThat(
                                                  RecordPublicationPatchDatabaseTest.update(
                                                      db, connection, row))
                                              .isEqualTo(1);
                                        entered.countDown();
                                        assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                                        connection.commit();
                                        return "committed";
                                      } catch (Exception failure) {
                                        throw new IllegalStateException(failure);
                                      }
                                    }));
                    try {
                      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                      var offline =
                          workers.submit(
                              () ->
                                  transactions.executeWithoutResult(
                                      status -> {
                                        offlinePid.set(
                                            ((Number)
                                                    em.createNativeQuery("SELECT pg_backend_pid()")
                                                        .getSingleResult())
                                                .intValue());
                                        var row =
                                            em.find(
                                                PhysicalResourceProjectionEntity.class,
                                                "target",
                                                LockModeType.PESSIMISTIC_WRITE);
                                        row.setFormallyOffline(true);
                                        row.setGeneration(8);
                                      }));
                      awaitLock(offlinePid);
                      assertThat(offline.isDone()).isFalse();
                      release.countDown();
                      assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo("committed");
                      offline.get(10, TimeUnit.SECONDS);
                    } finally {
                      release.countDown();
                    }
                  }
                  assertThatThrownBy(
                          () ->
                              fence.execute(
                                  "tenant-a",
                                  List.of(target, source),
                                  scopes,
                                  () -> {
                                    throw new AssertionError("Offline target reached execution");
                                  }))
                      .isInstanceOf(AccessDeniedException.class);
                  transactions.executeWithoutResult(
                      status -> {
                        var row = em.find(PhysicalResourceProjectionEntity.class, "target");
                        row.setFormallyOffline(false);
                        row.setGeneration(9);
                      });
                  assertThatThrownBy(
                          () ->
                              fence.execute(
                                  "tenant-a", List.of(source, target), scopes, () -> "stale"))
                      .isInstanceOf(AccessDeniedException.class);
                  var current =
                      new RecordResourceGuard(parent.physicalModelId(), "a-target", 9, true);
                  assertThat(
                          fence.execute(
                              "tenant-a", List.of(current, source), scopes, () -> "current"))
                      .isEqualTo("current");
                  transactions.executeWithoutResult(
                      status ->
                          em.find(PhysicalResourceProjectionEntity.class, "target")
                              .setPhysicalModelId(db.fixture().child.physicalModelId()));
                  assertThatThrownBy(
                          () ->
                              fence.execute(
                                  "tenant-a", List.of(source, current), scopes, () -> "retargeted"))
                      .isInstanceOf(AccessDeniedException.class);
                }));
  }

  private static PhysicalResourceProjectionEntity projection(
      String id, String resource, String model, long generation) {
    var row = new PhysicalResourceProjectionEntity();
    row.setId(id);
    row.setTenantId("tenant-a");
    row.setResourceCode(resource);
    row.setOwnerKey("app:" + id);
    row.setPhysicalModelId(model);
    row.setGeneration(generation);
    row.setCreatedBy("publisher");
    row.setUpdatedBy("publisher");
    row.setCreatedAt(Instant.now());
    row.setUpdatedAt(Instant.now());
    return row;
  }

  private static void awaitLock(AtomicInteger pid) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try (var connection =
            DriverManager.getConnection(
                System.getenv("DNA_RUNTIME_TEST_POSTGRES_URL"),
                System.getenv("DNA_RUNTIME_TEST_POSTGRES_USER"),
                System.getenv("DNA_RUNTIME_TEST_POSTGRES_PASSWORD"));
        var statement =
            connection.prepareStatement(
                "SELECT wait_event_type FROM pg_stat_activity WHERE pid=?")) {
      while (System.nanoTime() < deadline) {
        statement.setInt(1, pid.get());
        try (var rows = statement.executeQuery()) {
          if (rows.next() && "Lock".equals(rows.getString(1))) return;
        }
        Thread.sleep(20);
      }
    }
    throw new AssertionError("Related resource offline did not reach the database lock");
  }
}
