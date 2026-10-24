package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.resource.api.deployment.ResourceDeploymentChange;
import org.simplepoint.plugin.rbac.resource.service.properties.ResourceRegistrationProperties;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationCallerPolicy;
import org.simplepoint.security.resource.ResourceRegistrationException;
import org.simplepoint.security.resource.ResourceRegistrationException.Code;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

/**
 * Actual catalog transactions and persisted deployment history; no running OAuth server required.
 */
class ResourceRegistrationDeploymentTest extends ResourceCatalogTestSupport {
  @Test
  void readOnlyReceiptRequiresTheCompleteCurrentBatch() {
    UUID operation = UUID.randomUUID();
    var first = activation("one", 0, operation);
    var second = activation("two", 0, operation);
    assertThat(deployments.installed(List.of(first, second))).isFalse();
    assertThat(historyCount()).isZero();
    deployments.apply(List.of(first, second));
    assertThat(deployments.installed(List.of(second, first))).isTrue();
    assertRejected(() -> deployments.installed(List.of(first)), Code.DEPLOYMENT_CONFLICT);
    assertThat(historyCount()).isEqualTo(2);
    deployments.apply(List.of(activation("one", 1)));
    assertRejected(() -> deployments.installed(List.of(first, second)), Code.DEPLOYMENT_CONFLICT);
    assertThat(historyCount()).isEqualTo(3);
  }

  @AfterEach
  void clearCaller() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void activationFencesLegacyAndOlderClientsWhileRetainingResourceIdentity() {
    register("legacy-client", "Before");
    final String id = resource("page").getId();
    var first = activation("owner", 0);
    deployments.apply(List.of(first));
    assertRejected(() -> register("legacy-client", "Stale"), Code.GENERATION_REJECTED);
    register(first.clientId(), "First");
    var next = activation("owner", 1);
    deployments.apply(List.of(next));
    assertRejected(() -> register(first.clientId(), "Stale"), Code.GENERATION_REJECTED);
    register(next.clientId(), "Current");
    assertThat(resource("page").getId()).isEqualTo(id);
    assertThat(resource("page").getName()).isEqualTo("Current");
    assertThat(deployments.current("owner").orElseThrow().generation()).isEqualTo(2);
    // A valid service may not substitute another owner's name or skip the check with an empty set.
    assertRejected(() -> provider().sync("foreign", Set.of()), Code.ACCESS_DENIED);
    authenticate(first.clientId());
    assertRejected(() -> provider().sync("owner", Set.of()), Code.GENERATION_REJECTED);
    assertRejected(() -> provider().sync("owner", null), Code.GENERATION_REJECTED);
  }

  @Test
  void retirementCannotRestoreLegacyOrLocalPluginAuthorityAndPreservesData() {
    var first = activation("owner", 0);
    deployments.apply(List.of(first));
    register(first.clientId(), "Retained");
    var retire = new ResourceDeploymentChange("owner", 1, UUID.randomUUID(), null);
    deployments.apply(List.of(retire));
    assertThat(deployments.current("owner").orElseThrow().active()).isFalse();
    assertRejected(() -> register(first.clientId(), "Stale"), Code.GENERATION_REJECTED);
    assertRejected(() -> register("legacy-client", "Fallback"), Code.GENERATION_REJECTED);
    assertRejected(
        () -> service.sync("owner", Set.of(declaration("other", "/other", "Plugin"))),
        Code.GENERATION_REJECTED);
    assertRejected(() -> service.removeByOwner("owner"), Code.GENERATION_REJECTED);
    assertThat(resource("page").getName()).isEqualTo("Retained");
    var renewed = activation("owner", 2);
    deployments.apply(List.of(renewed));
    register(renewed.clientId(), "Renewed");
    assertRejected(() -> register(first.clientId(), "Old"), Code.GENERATION_REJECTED);
    assertThat(historyCount()).isEqualTo(3);
  }

  @Test
  void retriesAreExactAndHistoricalOperationsCannotReactivateSupersededClients() {
    var first = activation("owner", 0);
    var accepted = deployments.apply(List.of(first));
    assertThat(deployments.apply(List.of(first))).isEqualTo(accepted);
    assertThat(historyCount()).isEqualTo(1);
    assertRejected(
        () ->
            deployments.apply(
                List.of(
                    new ResourceDeploymentChange(
                        "owner", 0, first.operationId(), "other" + suffix(first.operationId())))),
        Code.DEPLOYMENT_CONFLICT);
    var next = activation("owner", 1);
    deployments.apply(List.of(next));
    assertRejected(() -> deployments.apply(List.of(first)), Code.DEPLOYMENT_CONFLICT);
    assertRejected(
        () ->
            deployments.apply(
                List.of(
                    new ResourceDeploymentChange(
                        "owner", 2, first.operationId(), first.clientId()))),
        Code.DEPLOYMENT_CONFLICT);
    assertThat(deployments.current("owner").orElseThrow().clientId()).isEqualTo(next.clientId());
    assertThat(historyCount()).isEqualTo(2);
  }

  @Test
  void batchRetryCannotAddDropOrReplaceOwnersButMayChangeInputOrder() {
    UUID operation = UUID.randomUUID();
    var first = activation("one", 0, operation);
    var second = activation("two", 0, operation);
    deployments.apply(List.of(first, second));
    assertThat(deployments.apply(List.of(second, first))).hasSize(2);
    assertRejected(() -> deployments.apply(List.of(first)), Code.DEPLOYMENT_CONFLICT);
    assertRejected(
        () -> deployments.apply(List.of(first, second, activation("three", 0, operation))),
        Code.DEPLOYMENT_CONFLICT);
    assertThat(historyCount()).isEqualTo(2);
    assertThat(deployments.current("three")).isEmpty();
  }

  @Test
  void conflictingBatchAndRolledBackActivationLeaveAllOwnersUnchanged() {
    deployments.apply(List.of(activation("owner", 0)));
    UUID rejectedBatch = UUID.randomUUID();
    assertRejected(
        () ->
            deployments.apply(
                List.of(
                    activation("new-owner", 0, rejectedBatch),
                    activation("owner", 0, rejectedBatch))),
        Code.DEPLOYMENT_CONFLICT);
    assertThat(deployments.current("new-owner")).isEmpty();
    var before = deployments.current("owner").orElseThrow();
    UUID batch = UUID.randomUUID();
    transaction.executeWithoutResult(
        status -> {
          deployments.apply(
              List.of(activation("owner", 1, batch), activation("new-owner", 0, batch)));
          status.setRollbackOnly();
        });
    assertThat(deployments.current("owner")).contains(before);
    assertThat(deployments.current("new-owner")).isEmpty();
    assertThat(historyCount()).isEqualTo(1);
  }

  @Test
  void malformedAndSharedClientsCannotBecomeAuthority() {
    var first = activation("owner", 0);
    assertRejected(
        () ->
            deployments.apply(
                List.of(
                    new ResourceDeploymentChange(
                        "owner", 0, first.operationId(), "legacy-client"))),
        Code.DEPLOYMENT_CONFLICT);
    assertRejected(
        () ->
            deployments.apply(
                List.of(
                    first,
                    new ResourceDeploymentChange(
                        "foreign", 0, first.operationId(), first.clientId()))),
        Code.DEPLOYMENT_CONFLICT);
    assertRejected(() -> deployments.apply(List.of(first, first)), Code.DEPLOYMENT_CONFLICT);
    assertRejected(
        () ->
            deployments.apply(
                List.of(
                    new ResourceDeploymentChange(
                        "owner", Long.MAX_VALUE, first.operationId(), first.clientId()))),
        Code.DEPLOYMENT_CONFLICT);
    assertThat(historyCount()).isZero();
  }

  @Test
  void competingInstallersCannotBothAdvanceAnExpectedGeneration() throws Exception {
    deployments.apply(List.of(activation("owner", 0)));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var attempts =
          List.of(activation("owner", 1), activation("owner", 1)).stream()
              .map(
                  change ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                            try {
                              deployments.apply(List.of(change));
                              return "applied";
                            } catch (ResourceRegistrationException conflict) {
                              assertThat(conflict.code()).isEqualTo(Code.DEPLOYMENT_CONFLICT);
                              return "conflict";
                            }
                          }))
              .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  attempts.get(0).get(10, TimeUnit.SECONDS),
                  attempts.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("applied", "conflict");
    }
    assertThat(deployments.current("owner").orElseThrow().generation()).isEqualTo(2);
    assertThat(historyCount()).isEqualTo(2);
  }

  @Test
  void activationWaitsForInFlightSnapshotAndFencesAllSubsequentOldWrites() throws Exception {
    var first = activation("owner", 0);
    deployments.apply(List.of(first));
    var next = activation("owner", 1);
    var started = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var pending =
          transaction.execute(
              status -> {
                register(first.clientId(), "In flight");
                var attempt =
                    executor.submit(
                        () -> {
                          started.countDown();
                          try {
                            return deployments.apply(List.of(next));
                          } finally {
                            finished.countDown();
                          }
                        });
                try {
                  assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                  assertThat(finished.await(250, TimeUnit.MILLISECONDS)).isFalse();
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(failure);
                }
                return attempt;
              });
      java.util.Objects.requireNonNull(pending).get(10, TimeUnit.SECONDS);
    }
    assertThat(resource("page").getName()).isEqualTo("In flight");
    assertRejected(() -> register(first.clientId(), "Late"), Code.GENERATION_REJECTED);
    register(next.clientId(), "New deployment");
  }

  @Test
  void absentGenerationSchemaNeverFallsBackToLegacyPolicy() {
    register("legacy-client", "Before");
    transaction.executeWithoutResult(
        status ->
            em.createNativeQuery("drop table simpoint_resource_registration_deployments")
                .executeUpdate());
    assertRejected(() -> register("legacy-client", "Fallback"), Code.CATALOG_UNAVAILABLE);
    assertThat(resource("page").getName()).isEqualTo("Before");
  }

  @Test
  void jsonProviderPreservesGenerationFailureAndExportsNeitherOldProtocolNorAdministration() {
    var first = activation("owner", 0);
    deployments.apply(List.of(first));
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(ResourceRegistrationSecurityTest.Router.class)
        .withBean(org.simplepoint.security.service.ResourceService.class, () -> service)
        .withBean(
            ResourceRegistrationCallerPolicy.class,
            () -> new ResourceRegistrationCallerPolicy(new ResourceRegistrationProperties()))
        .withBean(ResourceRegistrationProvider.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var dispatcher =
                  context.getBean(
                      com.simplepoint.service.router.invocation.ServiceInvocationDispatcher.class);
              var codec =
                  new com.simplepoint.service.router.invocation.ServiceRouterJsonCodec(
                      context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class));
              authenticate(first.clientId());
              byte[] request =
                  codec.encode(
                      new com.simplepoint.service.router.invocation.RemoteRequest(
                          "security.resource",
                          "4",
                          "sync",
                          List.of("owner", Set.of(declaration("page", "/page", "Accepted"))),
                          "current"));
              assertThat(codec.decodeResponse(dispatcher.dispatchJson(request)).success()).isTrue();
              deployments.apply(
                  List.of(new ResourceDeploymentChange("owner", 1, UUID.randomUUID(), null)));
              var stale = codec.decodeResponse(dispatcher.dispatchJson(request));
              assertThat(stale.errorCode()).isEqualTo("RESOURCE_REGISTRATION:GENERATION_REJECTED");
              assertThat(resource("page").getName()).isEqualTo("Accepted");
              for (var unsupported :
                  List.of(
                      new com.simplepoint.service.router.invocation.RemoteRequest(
                          "security.resource", "2", "sync", List.of("owner", Set.of()), "old"),
                      new com.simplepoint.service.router.invocation.RemoteRequest(
                          "security.resource", "3", "sync", List.of("owner", Set.of()), "old-v3"),
                      new com.simplepoint.service.router.invocation.RemoteRequest(
                          "security.resource", "4", "apply", List.of(List.of(first)), "admin"))) {
                assertThat(
                        codec
                            .decodeResponse(dispatcher.dispatchJson(codec.encode(unsupported)))
                            .success())
                    .isFalse();
              }
            });
  }

  private ResourceRegistrationProvider provider() {
    // Even a receiver whose static legacy policy knows no new clients observes the durable
    // authority.
    return new ResourceRegistrationProvider(
        service, new ResourceRegistrationCallerPolicy(new ResourceRegistrationProperties()));
  }

  private void register(String client, String name) {
    authenticate(client);
    provider().sync("owner", Set.of(declaration("page", "/page", name)));
  }

  private static void authenticate(String client) {
    List<org.springframework.security.core.GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("SCOPE_service-router.invoke"));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            "display",
            Map.of("client_id", client, "grant_type", "client_credentials"),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(principal, null, authorities));
  }

  private static ResourceDeploymentChange activation(String owner, long expected) {
    return activation(owner, expected, UUID.randomUUID());
  }

  private static ResourceDeploymentChange activation(String owner, long expected, UUID operation) {
    return new ResourceDeploymentChange(
        owner, expected, operation, "module-" + owner + suffix(operation));
  }

  private static String suffix(UUID operation) {
    return "-deployment-" + operation.toString().replace("-", "");
  }

  private long historyCount() {
    return read(
        () ->
            ((Number)
                    em.createNativeQuery(
                            "select count(*) from simpoint_resource_registration_deployments")
                        .getSingleResult())
                .longValue());
  }

  private static void assertRejected(Runnable action, Code code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ResourceRegistrationException.class,
            rejection -> assertThat(rejection.code()).isEqualTo(code));
  }
}
