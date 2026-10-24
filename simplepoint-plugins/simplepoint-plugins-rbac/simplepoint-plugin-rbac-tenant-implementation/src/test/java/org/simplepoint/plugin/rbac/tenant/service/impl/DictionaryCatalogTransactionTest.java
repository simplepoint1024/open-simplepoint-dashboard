package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryDeclaration;
import org.simplepoint.platform.dictionary.DictionaryItemDeclaration;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.error.DictionaryWriteException;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual JPA writers, transactional declaration application and database lock contention. */
class DictionaryCatalogTransactionTest extends DictionaryPersistenceTestSupport {
  @Test
  void repeatedDeclarationRetainsIdsAndEntersOncePerTransaction() {
    writer.apply(declaration("First", "ok"));
    var before = read(() -> em.createQuery("from Dictionary", Dictionary.class).getSingleResult());
    var beforeItem =
        read(() -> em.createQuery("from DictionaryItem", DictionaryItem.class).getSingleResult());
    writer.apply(declaration("Renamed", "ok"));
    var after = read(() -> em.createQuery("from Dictionary", Dictionary.class).getSingleResult());
    var afterItem =
        read(() -> em.createQuery("from DictionaryItem", DictionaryItem.class).getSingleResult());
    assertEquals(before.getId(), after.getId());
    assertEquals(beforeItem.getId(), afterItem.getId());
    assertEquals("Renamed", after.getName());
    assertEquals(2, revision());
  }

  @Test
  void lateDatabaseFailureRollsBackEntireBatchAndRevision() {
    writer.apply(declaration("Before", "ok"));
    transaction.executeWithoutResult(
        status ->
            em.createNativeQuery(
                    "alter table simpoint_saas_dictionary_items add constraint reject_test_value"
                        + " check(item_value <> 'reject')")
                .executeUpdate());
    var first = declaration("Must roll back", "ok").dictionaries().getFirst();
    var rejected =
        new DictionaryDeclaration(
            "second",
            "Second",
            null,
            20,
            List.of(new DictionaryItemDeclaration("reject", "Rejected", null, null, 10)));
    assertThrows(
        RuntimeException.class,
        () ->
            writer.apply(
                new DictionaryContribution("test", "seed", "2", 300, List.of(first, rejected))));
    assertEquals(
        "Before",
        read(
            () -> em.createQuery("from Dictionary", Dictionary.class).getSingleResult().getName()));
    assertEquals(
        1,
        read(
            () ->
                em.createQuery("from DictionaryItem", DictionaryItem.class)
                    .getResultList()
                    .size()));
    assertEquals(1, revision());
  }

  @Test
  void renameUpdatesChildCodesAndSingleDeleteRemovesChildren() {
    writer.apply(declaration("Original", "ok"));
    var dictionary =
        read(() -> em.createQuery("from Dictionary", Dictionary.class).getSingleResult());
    dictionary.setCode("renamed");
    dictionaries.modifyById(dictionary);
    assertEquals(
        "renamed",
        read(
            () ->
                em.createQuery("from DictionaryItem", DictionaryItem.class)
                    .getSingleResult()
                    .getDictionaryCode()));
    dictionaries.removeById(dictionary.getId());
    assertEquals(0, dictionaryCount());
    assertEquals(0, itemCount());
  }

  @Test
  void deleteAllUsesSameAggregateCleanup() {
    writer.apply(declaration("Original", "ok"));
    dictionaries.removeAll();
    assertEquals(0, dictionaryCount());
    assertEquals(0, itemCount());
  }

  @Test
  void batchCreateValidatesItemsAndRollsBackEarlierRows() {
    writer.apply(declaration("Original", "ok"));
    var valid = item("example", "good");
    var invalid = item("missing", "bad");
    assertThrows(IllegalArgumentException.class, () -> items.create(List.of(valid, invalid)));
    assertEquals(1, itemCount());
    assertEquals(1, revision());
    var created = items.create(List.of(item("example", "new")));
    assertTrue(created.getFirst().getEnabled());
    items.removeByIds(created.stream().map(DictionaryItem::getId).toList());
    items.removeAll();
    assertEquals(0, itemCount());
  }

  @Test
  void bulkDictionaryDefaultsAndItemSingleDeleteCannotBypassBoundary() {
    var dictionary = new Dictionary();
    dictionary.setName("Bulk");
    dictionary.setCode("example");
    assertTrue(dictionaries.create(List.of(dictionary)).getFirst().getEnabled());
    var item = items.create(item("example", "one"));
    items.removeById(item.getId());
    assertEquals(0, itemCount());
    assertEquals(3, revision());
  }

  @Test
  void nontransactionalReadonlyAndPreloadedContextsAreRejected() {
    assertEquals(
        DictionaryWriteException.Code.CONTEXT_CONFLICT,
        assertThrows(DictionaryWriteException.class, boundary::enter).code());
    var readOnly = new TransactionTemplate(manager);
    readOnly.setReadOnly(true);
    assertThrows(
        DictionaryWriteException.class,
        () -> readOnly.executeWithoutResult(status -> boundary.enter()));
    writer.apply(declaration("Original", "ok"));
    assertThrows(
        DictionaryWriteException.class,
        () ->
            transaction.executeWithoutResult(
                status -> {
                  var entity =
                      em.createQuery("from Dictionary", Dictionary.class).getSingleResult();
                  entity.setName("Dirty");
                  boundary.enter();
                }));
    assertEquals(
        "Original",
        read(
            () -> em.createQuery("from Dictionary", Dictionary.class).getSingleResult().getName()));
    assertEquals(1, revision());
  }

  @Test
  void missingRevisionRowFailsClosedBeforeBusinessWrites() {
    transaction.executeWithoutResult(
        status ->
            em.createNativeQuery("delete from simpoint_dictionary_catalog_revision")
                .executeUpdate());
    assertEquals(
        DictionaryWriteException.Code.UNAVAILABLE,
        assertThrows(
                DictionaryWriteException.class, () -> writer.apply(declaration("No write", "ok")))
            .code());
    assertEquals(0, dictionaryCount());
  }

  @Test
  void anotherWriterWaitsForCommitAndReadsCommittedMetadata() throws Exception {
    writer.apply(declaration("Initial", "ok"));
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempted = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        boundary.enter();
                        writer.apply(declaration("First", "ok"));
                        held.countDown();
                        await(release);
                      }));
      try {
        assertTrue(held.await(5, TimeUnit.SECONDS));
        var second =
            executor.submit(
                () -> {
                  attempted.countDown();
                  writer.apply(declaration("Second", "ok"));
                });
        assertTrue(attempted.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
    assertEquals(
        "Second",
        read(
            () -> em.createQuery("from Dictionary", Dictionary.class).getSingleResult().getName()));
    assertEquals(3, revision());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Latch timeout");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    }
  }
}
