package org.simplepoint.plugin.ai.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageResolution;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolUpsertRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimePoolRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeWorkloadRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeImageResolver;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSecretService;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeEgressPolicyCodec;

@ExtendWith(MockitoExtension.class)
class AiRuntimePoolServiceImplTest {

  @Mock
  private AiRuntimePoolRepository repository;

  @Mock
  private AiRuntimeWorkloadRepository workloadRepository;

  @Mock
  private AiScopeAccessPolicy scopeAccessPolicy;

  @Mock
  private AiRuntimeSecretService secretService;

  @Mock
  private AiRuntimeEgressPolicyCodec egressPolicyCodec;

  @Mock
  private AiRuntimeMcpAdmissionService admissionService;

  @Mock
  private AiRuntimeImageResolver imageResolver;

  private AiRuntimePoolServiceImpl service;

  private AiRuntimePool pool;

  @BeforeEach
  void setUp() {
    service = new AiRuntimePoolServiceImpl(
        repository,
        workloadRepository,
        new AiRuntimeProperties(),
        scopeAccessPolicy,
        secretService,
        egressPolicyCodec,
        admissionService,
        imageResolver
    );
    pool = new AiRuntimePool();
    pool.setId("pool-a");
    pool.setScopeType(AiResourceScope.SYSTEM);
    pool.setStatus(RuntimePoolStatus.READY);
    pool.setDesiredReplicas(1);
    pool.setCurrentReplicas(1);
    pool.setReadyReplicas(1);
    pool.setMaxReplicas(2);
    pool.setNetworkMode("none");
    pool.setEgressAllowlistJson("[]");
    pool.setSecretReferencesJson("[]");
  }

  @Test
  void redeployRequestsReplacementOfRunningReplicas() {
    AiRuntimeWorkload workload = workload(
        "workload-a",
        RuntimeWorkloadStatus.RUNNING
    );
    workload.setLeaseId("lease-a");
    workload.setLastObservedAt(Instant.now());
    when(repository.findActiveByIdForUpdate("pool-a"))
        .thenReturn(Optional.of(pool));
    when(workloadRepository.findActiveByPool("pool-a"))
        .thenReturn(List.of(workload));
    when(repository.save(pool)).thenReturn(pool);

    AiRuntimePool result = service.redeploy("pool-a");

    assertThat(result.getStatus()).isEqualTo(RuntimePoolStatus.SCALING);
    assertThat(workload.getStatus())
        .isEqualTo(RuntimeWorkloadStatus.STOPPING);
    assertThat(workload.getLastObservedAt()).isNull();
    assertThat(workload.getLastError())
        .isEqualTo("Runtime pool redeploy requested");
    verify(workloadRepository).save(workload);
    verify(admissionService).admitLegacy(pool);
  }

  @Test
  void invalidNewOciImageIsRejectedBeforePoolIsSaved() {
    when(scopeAccessPolicy.currentManagementScope()).thenReturn(
        new ScopeAssignment(AiResourceScope.SYSTEM, null)
    );
    when(repository.findActiveByCodeForUpdate(
        AiResourceScope.SYSTEM,
        null,
        "invalid-pool"
    )).thenReturn(Optional.empty());
    when(repository.findActiveByServerAndScope(
        "server-a",
        AiResourceScope.SYSTEM,
        null
    )).thenReturn(Optional.empty());
    when(egressPolicyCodec.normalize("none", List.of())).thenReturn("[]");
    when(secretService.normalizeReferences(
        List.of(),
        AiResourceScope.SYSTEM,
        null
    )).thenReturn("[]");
    org.mockito.Mockito.doThrow(new IllegalArgumentException(
        "OCI image failed MCP admission"
    )).when(admissionService).admitLegacy(org.mockito.ArgumentMatchers.any());

    assertThatThrownBy(() -> service.upsert(new RuntimePoolUpsertRequest(
        "invalid-pool",
        "Invalid Pool",
        "server-a",
        "postgres",
        "sha256:" + "a".repeat(64),
        256L * 1024 * 1024,
        500_000_000L,
        128,
        "none",
        List.of(),
        List.of(),
        0,
        2,
        1,
        1,
        0,
        300,
        3600
    )))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MCP admission");

    verify(repository, org.mockito.Mockito.never()).save(
        org.mockito.ArgumentMatchers.any()
    );
  }

  @Test
  void resolvesTagToDigestBeforeSavingNewPool() {
    String resolvedDigest = "sha256:" + "c".repeat(64);
    when(scopeAccessPolicy.currentManagementScope()).thenReturn(
        new ScopeAssignment(AiResourceScope.SYSTEM, null)
    );
    when(repository.findActiveByCodeForUpdate(
        AiResourceScope.SYSTEM,
        null,
        "tag-pool"
    )).thenReturn(Optional.empty());
    when(repository.findActiveByServerAndScope(
        "server-a",
        AiResourceScope.SYSTEM,
        null
    )).thenReturn(Optional.empty());
    when(egressPolicyCodec.normalize("none", List.of())).thenReturn("[]");
    when(secretService.normalizeReferences(
        List.of(),
        AiResourceScope.SYSTEM,
        null
    )).thenReturn("[]");
    when(imageResolver.resolve("ghcr.io/example/mcp:latest"))
        .thenReturn(new RuntimeImageResolution(resolvedDigest, "policy"));
    when(repository.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AiRuntimePool result = service.upsert(new RuntimePoolUpsertRequest(
        "tag-pool",
        "Tag Pool",
        "server-a",
        "ghcr.io/example/mcp:latest",
        null,
        256L * 1024 * 1024,
        500_000_000L,
        128,
        "none",
        List.of(),
        List.of(),
        0,
        2,
        1,
        1,
        0,
        300,
        3600
    ));

    assertThat(result.getImageReference())
        .isEqualTo("ghcr.io/example/mcp:latest");
    assertThat(result.getImageDigest()).isEqualTo(resolvedDigest);
    verify(imageResolver).resolve("ghcr.io/example/mcp:latest");
    verify(admissionService).admitLegacy(result);
  }

  @Test
  void profileManagedPoolCannotBeOverwrittenByLegacyEditor() {
    pool.setRuntimeProfileId("profile-a");
    when(scopeAccessPolicy.currentManagementScope()).thenReturn(
        new ScopeAssignment(AiResourceScope.SYSTEM, null)
    );
    when(repository.findActiveByCodeForUpdate(
        AiResourceScope.SYSTEM,
        null,
        "pool-a"
    )).thenReturn(Optional.of(pool));
    when(egressPolicyCodec.normalize("none", List.of())).thenReturn("[]");
    when(secretService.normalizeReferences(
        List.of(),
        AiResourceScope.SYSTEM,
        null
    )).thenReturn("[]");

    assertThatThrownBy(() -> service.upsert(new RuntimePoolUpsertRequest(
        "pool-a",
        "Pool A",
        "server-a",
        "ghcr.io/example/server:2",
        "sha256:" + "b".repeat(64),
        256L * 1024 * 1024,
        500_000_000L,
        128,
        "none",
        List.of(),
        List.of(),
        0,
        2,
        1,
        1,
        0,
        300,
        3600
    )))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Runtime Profile-managed");

    assertThat(pool.getRuntimeProfileId()).isEqualTo("profile-a");
    verify(repository, org.mockito.Mockito.never()).save(
        org.mockito.ArgumentMatchers.any()
    );
  }

  @Test
  void changingRuntimeDefinitionRequestsReplicaReplacement() {
    final String oldDigest = "sha256:" + "a".repeat(64);
    final String newDigest = "sha256:" + "b".repeat(64);
    pool.setCode("pool-a");
    pool.setName("Pool A");
    pool.setServerId("server-a");
    pool.setImageReference("example/mcp:old");
    pool.setImageDigest(oldDigest);
    pool.setRequestedMemoryBytes(64L * 1024 * 1024);
    pool.setRequestedNanoCpus(250_000_000L);
    pool.setRequestedPidsLimit(32);
    pool.setMinReplicas(1);
    pool.setActivationReplicas(1);
    pool.setPrewarmNodes(0);
    pool.setIdleTimeoutSeconds(300);
    pool.setReplicaLifetimeSeconds(3600);
    AiRuntimeWorkload workload = workload(
        "workload-a",
        RuntimeWorkloadStatus.RUNNING
    );
    workload.setLeaseId("lease-a");
    when(scopeAccessPolicy.currentManagementScope()).thenReturn(
        new ScopeAssignment(AiResourceScope.SYSTEM, null)
    );
    when(repository.findActiveByCodeForUpdate(
        AiResourceScope.SYSTEM,
        null,
        "pool-a"
    )).thenReturn(Optional.of(pool));
    when(repository.findActiveByServerAndScope(
        "server-a",
        AiResourceScope.SYSTEM,
        null
    )).thenReturn(Optional.of(pool));
    when(egressPolicyCodec.normalize("none", List.of())).thenReturn("[]");
    when(secretService.normalizeReferences(
        List.of(),
        AiResourceScope.SYSTEM,
        null
    )).thenReturn("[]");
    when(workloadRepository.findActiveByPool("pool-a"))
        .thenReturn(List.of(workload));
    when(repository.save(pool)).thenReturn(pool);

    AiRuntimePool result = service.upsert(new RuntimePoolUpsertRequest(
        "pool-a",
        "Pool A",
        "server-a",
        "example/mcp:new",
        newDigest,
        64L * 1024 * 1024,
        250_000_000L,
        32,
        "none",
        List.of(),
        List.of(),
        1,
        2,
        1,
        1,
        0,
        300,
        3600
    ));

    assertThat(result.getImageDigest()).isEqualTo(newDigest);
    assertThat(workload.getStatus())
        .isEqualTo(RuntimeWorkloadStatus.STOPPING);
    assertThat(workload.getLastError())
        .isEqualTo("Runtime pool definition changed");
    verify(workloadRepository).save(workload);
  }

  @Test
  void removeRejectsPoolWithActiveReplicas() {
    pool.setStatus(RuntimePoolStatus.DISABLED);
    AiRuntimeWorkload workload = workload(
        "workload-a",
        RuntimeWorkloadStatus.RUNNING
    );
    when(repository.findActiveByIdForUpdate("pool-a"))
        .thenReturn(Optional.of(pool));
    when(workloadRepository.findActiveByPool("pool-a"))
        .thenReturn(List.of(workload));

    assertThatThrownBy(() -> service.remove("pool-a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fully reclaimed");
  }

  @Test
  void removeSoftDeletesReclaimedPoolAndTerminalWorkloads() {
    pool.setStatus(RuntimePoolStatus.DISABLED);
    pool.setCurrentReplicas(0);
    pool.setReadyReplicas(0);
    AiRuntimeWorkload workload = workload(
        "workload-a",
        RuntimeWorkloadStatus.CANCELLED
    );
    when(repository.findActiveByIdForUpdate("pool-a"))
        .thenReturn(Optional.of(pool));
    when(workloadRepository.findActiveByPool("pool-a"))
        .thenReturn(List.of(workload));

    service.remove("pool-a");

    assertThat(pool.getDeletedAt()).isNotNull();
    assertThat(workload.getDeletedAt()).isEqualTo(pool.getDeletedAt());
    verify(workloadRepository).save(workload);
    verify(repository).save(pool);
  }

  private AiRuntimeWorkload workload(
      final String id,
      final RuntimeWorkloadStatus status
  ) {
    AiRuntimeWorkload workload = new AiRuntimeWorkload();
    workload.setId(id);
    workload.setPoolId(pool.getId());
    workload.setScopeType(AiResourceScope.SYSTEM);
    workload.setStatus(status);
    return workload;
  }
}
