package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.simplepoint.platform.dictionary.DictionaryDeclaration;
import org.simplepoint.platform.dictionary.registration.DictionaryInstallation;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationException;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationException.Code;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;

/** Durable approvals, atomic completion, stale-generation fencing and management ownership. */
class DictionaryRegistrationTransactionTest extends DictionaryPersistenceTestSupport {
  @Test
  void readOnlyReceiptRejectsPartialChangedOrRetiredApprovals() {
    UUID operation = UUID.randomUUID();
    var first = command(operation, declaration("Initial", "ok"), 0);
    var second = command(operation, other("second", "second-code"), 0);
    assertFalse(registrations.installed(List.of(first, second)));
    assertEquals(0, registrations.generation(first.moduleCode()));
    registrations.install(List.of(first, second));
    assertTrue(registrations.installed(List.of(second, first)));
    assertEquals(0, dictionaryCount());
    assertCode(Code.DEPLOYMENT_CONFLICT, () -> registrations.installed(List.of(first)));
    var changed = command(operation, declaration("Changed", "ok"), 0);
    assertCode(Code.DEPLOYMENT_CONFLICT, () -> registrations.installed(List.of(changed, second)));
    registrations.install(List.of(new DictionaryInstallation(UUID.randomUUID(), 1, first.moduleCode(), null, null)));
    assertCode(Code.DEPLOYMENT_CONFLICT, () -> registrations.installed(List.of(first, second)));
    assertEquals(0, dictionaryCount());
  }

  @Test
  void completionRetryDoesNotOverwriteLaterAdministrativeEdits() {
    var declaration = declaration("Initial", "ok");
    final var install = install(declaration, 0);
    assertEquals(0, dictionaryCount());
    final var receipt = application.apply(declaration, install.clientId());
    var dictionary =
        read(() -> em.createQuery("from Dictionary", Dictionary.class).getSingleResult());
    dictionary.setEnabled(false);
    dictionary.setName("Admin override");
    dictionaries.modifyById(dictionary);
    assertEquals(receipt, application.apply(declaration, install.clientId()));
    var restarted =
        transactional(
            new org.simplepoint.plugin.rbac.tenant.service.initialize
                .DictionaryRegistrationApplication(
                new org.simplepoint.plugin.rbac.tenant.service.initialize
                    .DictionaryRegistrationStore(em, boundary),
                dictionaries,
                items));
    assertEquals(receipt, restarted.apply(declaration, install.clientId()));
    var after = read(() -> em.find(Dictionary.class, dictionary.getId()));
    assertEquals("Admin override", after.getName());
    assertFalse(after.getEnabled());
    assertEquals(1, applied());
  }

  @Test
  void identicalContentAcrossDeploymentChangesRetainsAdministrativeOverrides() {
    var declaration = declaration("Initial", "ok");
    var initial = install(declaration, 0);
    application.apply(declaration, initial.clientId());
    var dictionary =
        read(() -> em.createQuery("from Dictionary", Dictionary.class).getSingleResult());
    dictionary.setEnabled(false);
    dictionaries.modifyById(dictionary);
    var moved = install(declaration, 1);
    assertEquals(2, application.apply(declaration, moved.clientId()).generation());
    assertFalse(read(() -> em.find(Dictionary.class, dictionary.getId()).getEnabled()));
    registrations.install(
        List.of(new DictionaryInstallation(UUID.randomUUID(), 2, "test", null, null)));
    var reactivated = install(declaration, 3);
    assertEquals(4, application.apply(declaration, reactivated.clientId()).generation());
    assertFalse(read(() -> em.find(Dictionary.class, dictionary.getId()).getEnabled()));
  }

  @Test
  void wrongCallerAndChangedContentCannotUseAnApproval() {
    var declaration = declaration("Initial", "ok");
    final var install = install(declaration, 0);
    assertCode(Code.ACCESS_DENIED, () -> application.apply(declaration, "foreign-client"));
    assertCode(
        Code.DECLARATION_MISMATCH,
        () -> application.apply(declaration("Changed", "ok"), install.clientId()));
    assertCode(
        Code.DECLARATION_MISMATCH,
        () -> application.apply(declaration("Initial", "different"), install.clientId()));
    assertEquals(0, dictionaryCount());
    assertEquals(0, applied());
  }

  @Test
  void dataFailureRollsBackCompletionAndAllowsExactRetry() {
    var declaration = declaration("Initial", "reject");
    final var install = install(declaration, 0);
    transaction.executeWithoutResult(
        status ->
            em.createNativeQuery(
                    "alter table simpoint_saas_dictionary_items add constraint reject_registration"
                        + " check(item_value <> 'reject')")
                .executeUpdate());
    assertThrows(RuntimeException.class, () -> application.apply(declaration, install.clientId()));
    assertEquals(0, dictionaryCount());
    assertEquals(0, itemCount());
    assertEquals(0, applied());
    transaction.executeWithoutResult(
        status ->
            em.createNativeQuery(
                    "alter table simpoint_saas_dictionary_items drop constraint"
                        + " reject_registration")
                .executeUpdate());
    application.apply(declaration, install.clientId());
    assertEquals(1, dictionaryCount());
    assertEquals(1, applied());
  }

  @Test
  void upgradeAndRetirementFenceOldClientsAndLegacyStartup() {
    var old = declaration("Old", "ok");
    var first = install(old, 0);
    application.apply(old, first.clientId());
    var next = declaration("New", "ok");
    var second = install(next, 1);
    assertCode(Code.ACCESS_DENIED, () -> application.apply(old, first.clientId()));
    assertCode(Code.DECLARATION_MISMATCH, () -> application.apply(old, second.clientId()));
    assertEquals(2, application.apply(next, second.clientId()).generation());
    assertCode(Code.NOT_APPROVED, () -> writer.apply(next));
    registrations.install(
        List.of(new DictionaryInstallation(UUID.randomUUID(), 2, "test", null, null)));
    assertCode(Code.ACCESS_DENIED, () -> application.apply(next, second.clientId()));
    assertCode(Code.NOT_APPROVED, () -> writer.apply(next));
    assertEquals(1, dictionaryCount());
    assertEquals(3, registrations.generation("test"));
    assertCode(Code.DEPLOYMENT_CONFLICT, () -> registrations.install(List.of(first)));
  }

  @Test
  void installerRetriesMustMatchWholeOperationAndExpectedGeneration() {
    var operation = UUID.randomUUID();
    var one = command(operation, declaration("One", "ok"), 0);
    var two = command(operation, other("other", "other.code"), 0);
    registrations.install(List.of(one, two));
    registrations.install(List.of(two, one));
    assertEquals(1, registrations.generation("test"));
    assertCode(Code.DEPLOYMENT_CONFLICT, () -> registrations.install(List.of(one)));
    assertCode(
        Code.DEPLOYMENT_CONFLICT,
        () ->
            registrations.install(
                List.of(command(operation, declaration("Changed", "ok"), 0), two)));
    assertCode(
        Code.DEPLOYMENT_CONFLICT,
        () ->
            registrations.install(
                List.of(command(UUID.randomUUID(), declaration("New", "ok"), 0))));
  }

  @Test
  void conflictingOwnerRollsBackEntireInstallationBatch() {
    install(declaration("Owned", "ok"), 0);
    UUID operation = UUID.randomUUID();
    assertCode(
        Code.OWNERSHIP_CONFLICT,
        () ->
            registrations.install(
                List.of(
                    command(operation, other("fresh", "fresh.code"), 0),
                    command(operation, other("foreign", "example"), 0))));
    assertEquals(0, registrations.generation("fresh"));
    assertEquals(0, registrations.generation("foreign"));
    assertEquals(1, scalar("select count(*) from simpoint_dictionary_registration_owners"));
    assertEquals(1, scalar("select count(*) from simpoint_dictionary_registration_operations"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"active", "deleted", "orphan"})
  void existingUnmanagedDataRequiresExplicitAdoption(String state) {
    transaction.executeWithoutResult(
        status -> {
          if (state.equals("orphan")) {
            em.createNativeQuery(
                    "insert into simpoint_saas_dictionary_items(id,dictionary_code,item_value,name)"
                        + " values ('old-item','example','ok','Existing')")
                .executeUpdate();
          } else {
            em.createNativeQuery(
                    "insert into simpoint_saas_dictionaries(id,code,name,enabled) values"
                        + " ('old','example','Existing',false)")
                .executeUpdate();
            if (state.equals("deleted")) {
              em.createNativeQuery(
                      "update simpoint_saas_dictionaries set deleted_at=current_timestamp where"
                          + " id='old'")
                  .executeUpdate();
            }
          }
        });
    assertCode(Code.LEGACY_ADOPTION_REQUIRED, () -> install(declaration("New", "ok"), 0));
    assertEquals(0, registrations.generation("test"));
    assertEquals(0, scalar("select count(*) from simpoint_dictionary_registration_operations"));
  }

  @Test
  void reservedCodesAndManagedIdentitiesCannotBeRecreatedRenamedOrDeleted() {
    var declaration = declaration("Owned", "ok");
    final var install = install(declaration, 0);
    var collision = new Dictionary();
    collision.setCode("example");
    collision.setName("Other");
    assertCode(Code.OWNERSHIP_CONFLICT, () -> dictionaries.create(collision));
    application.apply(declaration, install.clientId());
    var existing =
        read(() -> em.createQuery("from Dictionary", Dictionary.class).getSingleResult());
    existing.setCode("renamed");
    assertCode(Code.OWNERSHIP_CONFLICT, () -> dictionaries.modifyById(existing));
    assertCode(Code.OWNERSHIP_CONFLICT, () -> dictionaries.removeById(existing.getId()));
    assertCode(Code.OWNERSHIP_CONFLICT, dictionaries::removeAll);
    assertEquals(1, dictionaryCount());
    assertEquals(1, itemCount());
    assertFalse(
        org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryRegistrationWrites
            .allowsCreation("example"));
  }

  @Test
  void concurrentApplicationsReturnOneDurableCompletion() throws Exception {
    var declaration = declaration("Owned", "ok");
    final var install = install(declaration, 0);
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  transaction.execute(
                      status -> {
                        var result = application.apply(declaration, install.clientId());
                        held.countDown();
                        await(release);
                        return result;
                      }));
      try {
        assertTrue(held.await(5, TimeUnit.SECONDS));
        var second =
            executor.submit(
                () -> {
                  started.countDown();
                  return application.apply(declaration, install.clientId());
                });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
        release.countDown();
        assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    }
    assertEquals(1, dictionaryCount());
    assertEquals(1, itemCount());
    assertEquals(1, applied());
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

  private DictionaryInstallation install(DictionaryContribution declaration, long expected) {
    var command = command(UUID.randomUUID(), declaration, expected);
    registrations.install(List.of(command));
    return command;
  }

  private DictionaryInstallation command(
      UUID operation, DictionaryContribution declaration, long expected) {
    return new DictionaryInstallation(
        operation,
        expected,
        declaration.moduleCode(),
        ModuleClientPlan.deploymentClientId("module-" + declaration.moduleCode(), operation),
        declaration);
  }

  private DictionaryContribution other(String module, String code) {
    return new DictionaryContribution(
        module,
        "seed",
        "1",
        310,
        List.of(new DictionaryDeclaration(code, "Name", null, 10, List.of())));
  }

  private long applied() {
    return scalar(
        "select count(*) from simpoint_dictionary_registration_approvals where applied=true");
  }

  private long scalar(String sql) {
    return read(() -> ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
  }

  private static void assertCode(Code code, org.junit.jupiter.api.function.Executable action) {
    assertEquals(code, assertThrows(DictionaryRegistrationException.class, action).code());
  }
}
