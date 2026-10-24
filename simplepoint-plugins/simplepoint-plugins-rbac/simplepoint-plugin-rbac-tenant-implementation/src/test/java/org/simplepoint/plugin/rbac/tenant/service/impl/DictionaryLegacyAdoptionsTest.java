package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.platform.composition.ModuleClientPlan;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.registration.DictionaryInstallation;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Command;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.LegacyCompletion;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryLegacyAdoptions;

class DictionaryLegacyAdoptionsTest extends DictionaryPersistenceTestSupport {
  @Test
  void adoptionPreservesAllStorageAndCarriesCompletionIntoFirstDeployment() {
    var declaration = declaration("Original", "ok");
    writer.apply(declaration);
    items.create(item("example", "custom"));
    sql(
        "update simpoint_saas_dictionaries set"
            + " name='管理员名称',enabled=false,description='preserve',sort=42");
    sql("update simpoint_saas_dictionary_items set name='日本語',enabled=false");
    final var before = data();
    var command = command(declaration);
    var adoption = service().adopt(command);
    assertEquals(before, data());
    assertEquals(adoption, service().adopt(command));
    assertEquals(2, command.expected().items());
    var install = install(declaration, 0);
    assertEquals(1, application.apply(declaration, install.clientId()).generation());
    assertEquals(before, data());
    assertThrows(RuntimeException.class, () -> writer.apply(declaration));
    sql("update simpoint_saas_dictionaries set name='Later edit'");
    var later = data();
    assertEquals(adoption, service().adopt(command));
    assertEquals(later, data());
    var moved = install(declaration, 1);
    application.apply(declaration, moved.clientId());
    assertEquals(later, data());
  }

  @Test
  void laterAppliedUpgradeTakesPrecedenceOverHistoricalAdoption() {
    var original = declaration("Original", "ok");
    writer.apply(original);
    service().adopt(command(original));
    var first = install(original, 0);
    application.apply(original, first.clientId());
    var changed = declaration("Upgraded", "ok");
    var upgrade = install(changed, 1);
    application.apply(changed, upgrade.clientId());
    assertEquals("Upgraded", name());
    var rollback = install(original, 2);
    application.apply(original, rollback.clientId());
    assertEquals("Original", name());
  }

  @ParameterizedTest
  @ValueSource(strings = {"dictionary", "item"})
  void explicitAdoptionPreservesSoftDeletionWithoutResurrectingData(String kind) {
    var declaration = declaration("Original", "ok");
    writer.apply(declaration);
    sql(
        "update "
            + (kind.equals("dictionary")
                ? "simpoint_saas_dictionaries"
                : "simpoint_saas_dictionary_items")
            + " set deleted_at=current_timestamp");
    final var before = data();
    service().adopt(command(declaration));
    var install = install(declaration, 0);
    application.apply(declaration, install.clientId());
    assertEquals(before, data());
  }

  @ParameterizedTest
  @ValueSource(strings = {"dictionary", "item", "empty"})
  void missingRequiredIdentitiesCannotBeMarkedComplete(String kind) {
    var declaration = declaration("Original", "ok");
    if (!kind.equals("empty")) {
      writer.apply(declaration);
      sql(
          "delete from "
              + (kind.equals("dictionary")
                  ? "simpoint_saas_dictionaries"
                  : "simpoint_saas_dictionary_items"));
    }
    var error = assertThrows(IllegalStateException.class, () -> service().preview(declaration));
    assertTrue(error.getMessage().contains("DATA_CONFLICT"));
    assertEquals(0, adopted());
  }

  @Test
  void changedPreviewIsRejectedWithoutClaimingCodes() {
    var declaration = declaration("Original", "ok");
    writer.apply(declaration);
    var command = command(declaration);
    sql("update simpoint_saas_dictionary_items set name='Changed after preview'");
    var error = assertThrows(IllegalStateException.class, () -> service().adopt(command));
    assertTrue(error.getMessage().contains("PREVIEW_CHANGED"));
    assertEquals(0, adopted());
    assertEquals(0, owners());
    service().adopt(command(declaration));
    assertEquals(1, adopted());
  }

  @Test
  void ownershipAndDeploymentApprovalCannotBeOverwrittenByAdoption() {
    var declaration = declaration("Original", "ok");
    var approved = install(declaration, 0);
    application.apply(declaration, approved.clientId());
    assertThrows(IllegalStateException.class, () -> service().preview(declaration));
    var foreign =
        new DictionaryContribution("foreign", "seed", "1", 300, declaration.dictionaries());
    assertTrue(
        assertThrows(IllegalStateException.class, () -> service().preview(foreign))
            .getMessage()
            .contains("OWNERSHIP_CONFLICT"));
    assertEquals(0, adopted());
  }

  @Test
  void storageFailureRollsBackOwnershipAndReceiptAndRetryCanResume() {
    var declaration = declaration("Original", "ok");
    writer.apply(declaration);
    var command = command(declaration);
    final var before = data();
    sql(
        "alter table simpoint_dictionary_legacy_adoptions add constraint reject_adoption check"
            + " (module_code <> 'test')");
    assertThrows(RuntimeException.class, () -> service().adopt(command));
    assertEquals(0, owners());
    assertEquals(0, adopted());
    assertEquals(before, data());
    sql("alter table simpoint_dictionary_legacy_adoptions drop constraint reject_adoption");
    service().adopt(command);
    assertEquals(1, owners());
  }

  @Test
  void retryCannotChangeTheRecordedOperationOrRegistryEvidence() {
    var declaration = declaration("Original", "ok");
    writer.apply(declaration);
    var command = command(declaration);
    service().adopt(command);
    var original = command.completion();
    for (var receipt :
        List.of(
            new LegacyCompletion(
                UUID.randomUUID(),
                original.targetScope(),
                original.moduleCode(),
                original.contributionKey(),
                original.version(),
                original.checksum(),
                original.sourceFingerprint()),
            new LegacyCompletion(
                original.operationId(),
                original.targetScope(),
                original.moduleCode(),
                original.contributionKey(),
                original.version(),
                original.checksum(),
                "b".repeat(64)))) {
      assertThrows(
          IllegalStateException.class,
          () -> service().adopt(new Command(declaration, command.expected(), receipt)));
    }
    var originalPreview = command.expected();
    var changedPreview =
        new org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Preview(
            originalPreview.declarationFingerprint(),
            originalPreview.dataFingerprint(),
            originalPreview.dictionaries(),
            originalPreview.items() + 1);
    assertThrows(
        IllegalStateException.class,
        () -> service().adopt(new Command(declaration, changedPreview, original)));
    assertEquals(1, adopted());
  }

  @Test
  void concurrentAdoptionSharesOneDurableCompletion() throws Exception {
    var declaration = declaration("Original", "ok");
    writer.apply(declaration);
    var command = command(declaration);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      final var first =
          workers.submit(
              () ->
                  transaction.execute(
                      status -> {
                        var receipt = service().adopt(command);
                        locked.countDown();
                        await(release);
                        return receipt;
                      }));
      assertTrue(locked.await(10, TimeUnit.SECONDS));
      var attempted = new CountDownLatch(1);
      var second =
          workers.submit(
              () -> {
                attempted.countDown();
                return service().adopt(command);
              });
      assertTrue(attempted.await(10, TimeUnit.SECONDS));
      try {
        assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
      }
      assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
    assertEquals(1, adopted());
    assertEquals(1, owners());
  }

  private Command command(DictionaryContribution declaration) {
    return new Command(
        declaration,
        service().preview(declaration),
        new LegacyCompletion(
            UUID.randomUUID(),
            "owner/identity/dictionary",
            declaration.moduleCode(),
            declaration.contributionKey(),
            declaration.version(),
            declaration.moduleCode()
                + ":dictionary:"
                + declaration.contributionKey()
                + ":"
                + declaration.version(),
            "a".repeat(64)));
  }

  private DictionaryLegacyAdoptions service() {
    return transactional(new DictionaryLegacyAdoptions(em, boundary));
  }

  private DictionaryInstallation install(DictionaryContribution declaration, long generation) {
    var operation = UUID.randomUUID();
    var command =
        new DictionaryInstallation(
            operation,
            generation,
            declaration.moduleCode(),
            ModuleClientPlan.deploymentClientId("fixture", operation),
            declaration);
    registrations.install(List.of(command));
    return command;
  }

  private List<String> data() {
    return read(
        () -> {
          List<?> dictionaryRows =
              em.createNativeQuery("select * from simpoint_saas_dictionaries order by id")
                  .getResultList();
          List<?> itemRows =
              em.createNativeQuery("select * from simpoint_saas_dictionary_items order by id")
                  .getResultList();
          return java.util.stream.Stream.concat(dictionaryRows.stream(), itemRows.stream())
              .map(row -> Arrays.deepToString((Object[]) row))
              .toList();
        });
  }

  private String name() {
    return read(
        () ->
            (String)
                em.createNativeQuery("select name from simpoint_saas_dictionaries")
                    .getSingleResult());
  }

  private void sql(String statement) {
    transaction.executeWithoutResult(status -> em.createNativeQuery(statement).executeUpdate());
  }

  private long adopted() {
    return read(
        () ->
            ((Number)
                    em.createNativeQuery(
                            "select count(*) from simpoint_dictionary_legacy_adoptions")
                        .getSingleResult())
                .longValue());
  }

  private long owners() {
    return read(
        () ->
            ((Number)
                    em.createNativeQuery(
                            "select count(*) from simpoint_dictionary_registration_owners")
                        .getSingleResult())
                .longValue());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for adoption transaction release");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
