package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.manifest.PluginManifest;
import org.simplepoint.plugin.rbac.resource.api.repository.RemoteModuleRepository;
import org.simplepoint.plugin.rbac.resource.base.repository.JpaResourceRepository;
import org.simplepoint.plugin.rbac.resource.service.plugin.PluginResourceContributionHandler;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.resource.ResourceRegistrationException;
import org.simplepoint.security.resource.ResourceRegistrationException.Code;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Competing physical transactions through separate services, never an in-memory catalog lock. */
class ResourceCatalogConcurrencyTest extends ResourceCatalogTestSupport {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completeSnapshotsSerializeEvenWhenCatalogWasEmpty(boolean existing) throws Exception {
    if (existing) {
      service.sync("owner", Set.of(declaration("old", "/old", "Old")));
    }
    var second = newService();
    compete(
        () -> service.sync("owner", Set.of(declaration("first", "/first", "First"))),
        () -> second.sync("owner", Set.of(declaration("second", "/second", "Second"))),
        false);
    assertThat(codes()).containsExactly("second");
    assertThat(revision()).isEqualTo(existing ? 3 : 2);
  }

  @Test
  void failedWriterReleasesLockWithoutCommittingItsRevisionOrResources() throws Exception {
    compete(
        () -> service.sync("owner", Set.of(declaration("first", "/first", "First"))),
        () -> newService().sync("owner", Set.of(declaration("second", "/second", "Second"))),
        true);
    assertThat(codes()).containsExactly("second");
    assertThat(revision()).isEqualTo(1);
  }

  @Test
  void competingOwnersCannotClaimSamePathAfterWaiting() throws Exception {
    AtomicReference<Throwable> rejection = new AtomicReference<>();
    compete(
        () -> service.sync("first", Set.of(declaration("first", "/page", "First"))),
        () -> {
          try {
            newService().sync("second", Set.of(declaration("second", "/page", "Second")));
          } catch (ResourceRegistrationException failure) {
            rejection.set(failure);
          }
        },
        false);
    assertThat(rejection.get())
        .isInstanceOfSatisfying(
            ResourceRegistrationException.class,
            failure -> assertThat(failure.code()).isEqualTo(Code.OWNERSHIP_CONFLICT));
    assertThat(codes()).containsExactly("first");
    assertThat(revision()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"batch-create", "remove-one", "remove-all", "remove-owner"})
  void everyManagementWriteSharesTheRegistrationBoundary(String operation) throws Exception {
    service.sync("owner", Set.of(declaration("old", "/old", "Old")));
    String id = resource("old").getId();
    compete(
        () -> service.sync("owner", Set.of(declaration("old", "/old", "Changed"))),
        () -> {
          var context = new org.simplepoint.core.AuthorizationContext();
          context.setUserId("catalog-test-operator");
          context.setScopeType(org.simplepoint.core.AuthorizationScopeType.PLATFORM);
          context.setIsAdministrator(true);
          org.simplepoint.core.RequestContextHolder.setContext(
              org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
          try {
            switch (operation) {
              case "batch-create" -> {
                Resource item = new Resource();
                item.setCode("new");
                item.setName("New");
                service.create(List.of(item));
              }
              case "remove-one" -> service.removeById(id);
              case "remove-all" -> service.removeAll();
              case "remove-owner" -> service.removeByOwner("owner");
              default -> throw new AssertionError(operation);
            }
          } finally {
            org.simplepoint.core.RequestContextHolder.clearContext(
                org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
          }
        },
        false);
    assertThat(revision()).isEqualTo(3);
    if (operation.equals("batch-create")) {
      assertThat(codes()).containsExactlyInAnyOrder("new", "old");
    } else {
      assertThat(codes()).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void alreadyLoadedCatalogEntitiesAreRejectedWithoutFlushingOrDiscardingChanges(boolean dirty) {
    service.sync("owner", Set.of(declaration("old", "/old", "Old")));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      Resource loaded =
                          em.createQuery("from Resource", Resource.class).getSingleResult();
                      if (dirty) {
                        loaded.setName("Uncommitted");
                      }
                      service.sync("owner", Set.of(declaration("new", "/new", "New")));
                    }))
        .isInstanceOfSatisfying(
            ResourceRegistrationException.class,
            failure -> assertThat(failure.code()).isEqualTo(Code.CATALOG_CONTEXT_CONFLICT));
    assertThat(resource("old").getName()).isEqualTo("Old");
    assertThat(revision()).isEqualTo(1);
  }

  @Test
  void joinedOperationsReuseLockAndRollbackTogether() {
    transaction.executeWithoutResult(
        status -> {
          boundary.enter();
          service.sync("first", Set.of(declaration("first", "/first", "First")));
          service.sync("second", Set.of(declaration("second", "/second", "Second")));
          assertThat(revision()).isEqualTo(1);
          status.setRollbackOnly();
        });
    assertThat(codes()).isEmpty();
    assertThat(revision()).isZero();
    service.sync("third", Set.of(declaration("third", "/third", "Third")));
    assertThat(revision()).isEqualTo(1);
  }

  @Test
  void missingRevisionOrReadOnlyOrAbsentTransactionNeverSilentlyDisablesLocking() {
    assertThatThrownBy(boundary::enter).isInstanceOf(ResourceRegistrationException.class);
    var readOnly = new TransactionTemplate(manager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> boundary.enter()))
        .isInstanceOf(ResourceRegistrationException.class);
    transaction.executeWithoutResult(
        status ->
            em.createNativeQuery("delete from simpoint_resource_catalog_revision").executeUpdate());
    assertThatThrownBy(() -> service.sync("owner", Set.of(declaration("new", "/new", "New"))))
        .isInstanceOfSatisfying(
            ResourceRegistrationException.class,
            failure -> assertThat(failure.code()).isEqualTo(Code.CATALOG_UNAVAILABLE));
    assertThat(codes()).isEmpty();
  }

  @Test
  void olderRepeatableReadSnapshotCannotRegisterAfterAnotherWriterCommits() throws Exception {
    var snapshot = new TransactionTemplate(manager);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    try (var executor = Executors.newSingleThreadExecutor()) {
      assertThatThrownBy(
              () ->
                  snapshot.executeWithoutResult(
                      status -> {
                        assertThat(revision()).isZero();
                        try {
                          executor
                              .submit(
                                  () ->
                                      newService()
                                          .sync(
                                              "first",
                                              Set.of(declaration("first", "/first", "First"))))
                              .get(10, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                          throw new AssertionError(failure);
                        }
                        service.sync("second", Set.of(declaration("second", "/second", "Second")));
                      }))
          .isInstanceOfSatisfying(
              ResourceRegistrationException.class,
              failure -> assertThat(failure.code()).isEqualTo(Code.CATALOG_UNAVAILABLE));
    }
    assertThat(codes()).containsExactly("first");
    assertThat(revision()).isEqualTo(1);
  }

  @Test
  void ownerUninstallProtectsForeignDescendantsEvenWithMissingClosure() {
    service.sync("owner", Set.of(declaration("root", "/root", "Root")));
    service.sync("foreign", Set.of(declaration("foreign", "/foreign", "Foreign")));
    String rootId = resource("root").getId();
    transaction.executeWithoutResult(
        status -> {
          boundary.enter();
          Resource child =
              em.createQuery("from Resource where code='foreign'", Resource.class)
                  .getSingleResult();
          child.setParentId(rootId);
        });
    assertThatThrownBy(() -> service.removeByOwner("owner"))
        .isInstanceOf(ResourceRegistrationException.class);
    assertThat(codes()).containsExactlyInAnyOrder("root", "foreign");
  }

  @Test
  void pluginInstallAndUninstallSerializeWithRegistrationAndRetainOuterRollback() throws Exception {
    var repositories = new JpaRepositoryFactory(em);
    repositories.setRepositoryBaseClass(BaseRepositoryImpl.class);
    final var handler =
        new PluginResourceContributionHandler(
            mock(RemoteModuleRepository.class),
            repositories.getRepository(JpaResourceRepository.class),
            service,
            boundary);
    var manifest = new PluginManifest();
    manifest.setId("plugin");
    var root = new PluginManifest.ResourceContribution();
    root.setCode("plugin.root");
    root.setName("Plugin");
    root.setType("MODULE");
    manifest.setResources(List.of(root));
    var plugin = new Plugin(java.net.URI.create("file:/test/plugin.jar"), manifest, Map.of());

    compete(
        () -> service.sync("plugin", Set.of(declaration("old", "/old", "Old"))),
        () -> transaction.executeWithoutResult(status -> handler.installed(plugin)),
        false);
    assertThat(codes()).containsExactly("plugin.root");
    assertThat(revision()).isEqualTo(2);
    transaction.executeWithoutResult(
        status -> {
          handler.uninstalling(plugin);
          assertThat(codes()).isEmpty();
          status.setRollbackOnly();
        });
    assertThat(codes()).containsExactly("plugin.root");
    assertThat(revision()).isEqualTo(2);
    compete(
        () -> transaction.executeWithoutResult(status -> handler.installed(plugin)),
        () -> transaction.executeWithoutResult(status -> handler.uninstalling(plugin)),
        false);
    assertThat(codes()).isEmpty();
    assertThat(revision()).isEqualTo(4);
  }

  private List<String> codes() {
    return read(
        () ->
            em.createQuery("select r.code from Resource r order by r.code", String.class)
                .getResultList());
  }

  private long revision() {
    return read(
        () ->
            ((Number)
                    em.createNativeQuery(
                            "select revision from simpoint_resource_catalog_revision where"
                                + " id='catalog'")
                        .getSingleResult())
                .longValue());
  }

  private void compete(Runnable first, Runnable second, boolean rollback) throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var future =
          transaction.execute(
              status -> {
                first.run();
                em.flush();
                var pending =
                    executor.submit(
                        () -> {
                          started.countDown();
                          try {
                            second.run();
                          } finally {
                            finished.countDown();
                          }
                        });
                try {
                  assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                  assertThat(finished.await(250, TimeUnit.MILLISECONDS)).isFalse();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(interrupted);
                }
                if (rollback) {
                  status.setRollbackOnly();
                }
                return pending;
              });
      java.util.Objects.requireNonNull(future).get(10, TimeUnit.SECONDS);
    }
  }
}
