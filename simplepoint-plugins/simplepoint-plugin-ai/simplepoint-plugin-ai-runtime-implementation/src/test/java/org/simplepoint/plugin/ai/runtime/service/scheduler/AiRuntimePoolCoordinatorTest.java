package org.simplepoint.plugin.ai.runtime.service.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimePoolRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeWorkloadRepository;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeImageCacheCodec;

class AiRuntimePoolCoordinatorTest {

  private AiRuntimePoolRepository poolRepository;

  private AiRuntimeNodeRepository nodeRepository;

  private AiRuntimeWorkloadRepository workloadRepository;

  private AiRuntimePoolCoordinator coordinator;

  @BeforeEach
  void setUp() {
    poolRepository = mock(AiRuntimePoolRepository.class);
    nodeRepository = mock(AiRuntimeNodeRepository.class);
    workloadRepository = mock(AiRuntimeWorkloadRepository.class);
    AiRuntimeProperties properties = new AiRuntimeProperties();
    properties.setPoolSchedulerBatchSize(16);
    properties.setPoolPrewarmRetryInterval(Duration.ofSeconds(30));
    coordinator = new AiRuntimePoolCoordinator(
        poolRepository,
        nodeRepository,
        workloadRepository,
        properties,
        new AiRuntimeImageCacheCodec(new ObjectMapper())
    );
    when(poolRepository.save(any())).thenAnswer(invocation ->
        invocation.getArgument(0));
  }

  @Test
  void shouldClaimOnlyMissingPrewarmAcrossCapableNodes() {
    AiRuntimePool pool = pool();
    pool.setPrewarmNodes(2);
    AiRuntimeNode cached = node("node-a", true);
    AiRuntimeNode missing = node("node-b", false);
    when(poolRepository.findReconcileForUpdate(any(), any()))
        .thenReturn(List.of(pool));
    when(nodeRepository.findSchedulableNodesForUpdate(any()))
        .thenReturn(List.of(cached, missing));

    var tasks = coordinator.claimPrewarms();

    assertEquals(1, tasks.size());
    assertEquals("node-b", tasks.getFirst().nodeId());
    assertEquals(1, pool.getPrewarmedNodes());
    assertTrue(pool.getLastPrewarmAt() != null);
  }

  @Test
  void shouldCreateDesiredReplicasAfterMultiNodePrewarm() {
    AiRuntimePool pool = pool();
    pool.setPrewarmNodes(2);
    List<AiRuntimeWorkload> replicas = new ArrayList<>();
    when(poolRepository.findReconcileForUpdate(any(), any()))
        .thenReturn(List.of(pool));
    when(nodeRepository.findSchedulableNodesForUpdate(any()))
        .thenReturn(List.of(node("node-a", true), node("node-b", true)));
    when(workloadRepository.findActiveByPool("pool-1"))
        .thenAnswer(ignored -> replicas);
    when(workloadRepository.save(any())).thenAnswer(invocation -> {
      AiRuntimeWorkload workload = invocation.getArgument(0);
      workload.setId("workload-" + (replicas.size() + 1));
      replicas.add(workload);
      return workload;
    });

    assertEquals(2, coordinator.reconcile());

    assertEquals(2, replicas.size());
    assertEquals(1L, replicas.get(0).getReplicaSequence());
    assertEquals(2L, replicas.get(1).getReplicaSequence());
    assertEquals(2, pool.getCurrentReplicas());
    assertEquals(RuntimePoolStatus.SCALING, pool.getStatus());
  }

  @Test
  void shouldScaleToZeroAfterIdleTimeout() {
    AiRuntimePool pool = pool();
    pool.setMinReplicas(0);
    pool.setIdleTimeoutSeconds(30);
    pool.setLastActivityAt(Instant.now().minusSeconds(60));
    AiRuntimeWorkload workload = replica(RuntimeWorkloadStatus.RUNNING);
    when(poolRepository.findReconcileForUpdate(any(), any()))
        .thenReturn(List.of(pool));
    when(nodeRepository.findSchedulableNodesForUpdate(any()))
        .thenReturn(List.of());
    when(workloadRepository.findActiveByPool("pool-1"))
        .thenReturn(List.of(workload));
    when(workloadRepository.save(any())).thenAnswer(invocation ->
        invocation.getArgument(0));

    assertEquals(1, coordinator.reconcile());

    assertEquals(RuntimeWorkloadStatus.STOPPING, workload.getStatus());
    assertEquals(RuntimePoolStatus.SCALING, pool.getStatus());
  }

  @Test
  void shouldOpenCircuitAfterThreeConsecutiveReplicaFailures() {
    AiRuntimePool pool = pool();
    List<AiRuntimeWorkload> failures = new ArrayList<>();
    for (int index = 1; index <= 3; index++) {
      AiRuntimeWorkload failure = replica(RuntimeWorkloadStatus.FAILED);
      failure.setId("workload-" + index);
      failure.setReplicaSequence((long) index);
      failure.setCreatedAt(Instant.now());
      failure.setLastError("OCI workload exited with code 1");
      failures.add(failure);
    }
    when(poolRepository.findReconcileForUpdate(any(), any()))
        .thenReturn(List.of(pool));
    when(nodeRepository.findSchedulableNodesForUpdate(any()))
        .thenReturn(List.of(node("node-a", true)));
    when(workloadRepository.findActiveByPool("pool-1"))
        .thenReturn(failures);

    assertEquals(0, coordinator.reconcile());

    assertEquals(RuntimePoolStatus.ERROR, pool.getStatus());
    assertTrue(pool.getLastError().contains("repeated replica failures"));
    assertEquals(0, pool.getCurrentReplicas());
  }

  @Test
  void shouldKeepDisabledPoolDisabledDespiteHistoricalFailures() {
    AiRuntimePool pool = pool();
    pool.setStatus(RuntimePoolStatus.DISABLED);
    pool.setDesiredReplicas(0);
    List<AiRuntimeWorkload> failures = new ArrayList<>();
    for (int index = 1; index <= 3; index++) {
      AiRuntimeWorkload failure = replica(RuntimeWorkloadStatus.FAILED);
      failure.setId("disabled-workload-" + index);
      failure.setReplicaSequence((long) index);
      failure.setCreatedAt(Instant.now());
      failure.setLastError("OCI workload exited with code 1");
      failures.add(failure);
    }
    when(poolRepository.findReconcileForUpdate(any(), any()))
        .thenReturn(List.of(pool));
    when(nodeRepository.findSchedulableNodesForUpdate(any()))
        .thenReturn(List.of(node("node-a", true)));
    when(workloadRepository.findActiveByPool("pool-1"))
        .thenReturn(failures);

    assertEquals(0, coordinator.reconcile());

    assertEquals(RuntimePoolStatus.DISABLED, pool.getStatus());
    assertEquals(0, pool.getCurrentReplicas());
    assertEquals(0, pool.getReadyReplicas());
  }

  private AiRuntimePool pool() {
    AiRuntimePool pool = new AiRuntimePool();
    pool.setId("pool-1");
    pool.setScopeType(AiResourceScope.SYSTEM);
    pool.setCode("weather");
    pool.setName("Weather");
    pool.setServerId("server-1");
    pool.setImageReference("somesimpled/weather");
    pool.setImageDigest("sha256:" + "a".repeat(64));
    pool.setRequestedMemoryBytes(256L * 1024 * 1024);
    pool.setRequestedNanoCpus(500_000_000L);
    pool.setRequestedPidsLimit(128);
    pool.setNetworkMode("none");
    pool.setEgressAllowlistJson("[]");
    pool.setSecretReferencesJson("[]");
    pool.setMinReplicas(0);
    pool.setMaxReplicas(4);
    pool.setDesiredReplicas(2);
    pool.setActivationReplicas(1);
    pool.setPrewarmNodes(0);
    pool.setIdleTimeoutSeconds(0);
    pool.setReplicaLifetimeSeconds(3600);
    pool.setNextReplicaSequence(1);
    pool.setStatus(RuntimePoolStatus.SCALING);
    pool.setLastActivityAt(Instant.now());
    return pool;
  }

  private AiRuntimeNode node(final String nodeId, final boolean cached) {
    AiRuntimeNode node = new AiRuntimeNode();
    node.setNodeId(nodeId);
    node.setInstanceId("instance-" + nodeId);
    node.setAdvertiseUrl("https://" + nodeId + ":2891");
    node.setStatus(RuntimeNodeStatus.READY);
    node.setHeartbeatExpiresAt(Instant.now().plusSeconds(30));
    node.setCpuCores(8);
    node.setMemoryBytes(16L * 1024 * 1024 * 1024);
    node.setMaxWorkloads(32);
    node.setMaxWorkloadMemoryBytes(2L * 1024 * 1024 * 1024);
    node.setMaxWorkloadNanoCpus(4_000_000_000L);
    node.setMaxWorkloadPidsLimit(512);
    node.setCachedImageDigestsJson(cached
        ? "[\"sha256:" + "a".repeat(64) + "\"]" : "[]");
    return node;
  }

  private AiRuntimeWorkload replica(final RuntimeWorkloadStatus status) {
    AiRuntimeWorkload workload = new AiRuntimeWorkload();
    workload.setId("workload-1");
    workload.setPoolId("pool-1");
    workload.setReplicaSequence(1L);
    workload.setStatus(status);
    workload.setLeaseId("lease-1");
    return workload;
  }
}
