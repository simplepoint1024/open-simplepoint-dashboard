package org.simplepoint.plugin.ai.runtime.service.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeLease;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeLeaseStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadObservation;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeLeaseRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeWorkloadRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSecretService;
import org.simplepoint.plugin.ai.runtime.service.scheduler.AiRuntimeWorkloadCoordinator.DispatchTask;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeEgressPolicyCodec;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeImageCacheCodec;

class AiRuntimeWorkloadCoordinatorTest {

  private AiRuntimeNodeRepository nodeRepository;

  private AiRuntimeWorkloadRepository workloadRepository;

  private AiRuntimeLeaseRepository leaseRepository;

  private AiRuntimeWorkloadCoordinator coordinator;

  private AiRuntimeSecretService secretService;

  private final AtomicInteger leaseSequence = new AtomicInteger();

  @BeforeEach
  void setUp() {
    nodeRepository = mock(AiRuntimeNodeRepository.class);
    workloadRepository = mock(AiRuntimeWorkloadRepository.class);
    leaseRepository = mock(AiRuntimeLeaseRepository.class);
    secretService = mock(AiRuntimeSecretService.class);
    AiRuntimeProperties properties = new AiRuntimeProperties();
    properties.setWorkloadLeaseDuration(Duration.ofSeconds(45));
    properties.setWorkloadDispatchRetryInterval(Duration.ofSeconds(5));
    properties.setWorkloadObservationInterval(Duration.ofSeconds(5));
    properties.setWorkloadSchedulerBatchSize(16);
    coordinator = new AiRuntimeWorkloadCoordinator(
        nodeRepository,
        workloadRepository,
        leaseRepository,
        properties,
        secretService,
        new AiRuntimeEgressPolicyCodec(new ObjectMapper()),
        new AiRuntimeImageCacheCodec(new ObjectMapper())
    );
    when(workloadRepository.save(any())).thenAnswer(invocation ->
        invocation.getArgument(0));
    when(secretService.resolve(any(), any(), any())).thenReturn(List.of());
    when(leaseRepository.save(any())).thenAnswer(invocation -> {
      AiRuntimeLease lease = invocation.getArgument(0);
      if (lease.getId() == null) {
        lease.setId("lease-" + leaseSequence.incrementAndGet());
      }
      return lease;
    });
  }

  @Test
  void shouldAssignByCapacityAndCreateFirstFence() {
    final AiRuntimeWorkload workload = pendingWorkload();
    AiRuntimeNode node = readyNode();
    when(workloadRepository.findPendingForUpdate(any()))
        .thenReturn(List.of(workload));
    when(nodeRepository.findSchedulableNodesForUpdate(any()))
        .thenReturn(List.of(node));
    when(leaseRepository.findByNodeAndStatus(
        "node-a",
        RuntimeLeaseStatus.ACTIVE
    )).thenReturn(List.of());

    int assigned = coordinator.assignPending();

    assertEquals(1, assigned);
    assertEquals(RuntimeWorkloadStatus.ASSIGNED, workload.getStatus());
    assertEquals("node-a", workload.getAssignedNodeId());
    assertEquals("lease-1", workload.getLeaseId());
    assertEquals(1L, workload.getFencingToken());
    verify(leaseRepository).save(any(AiRuntimeLease.class));
  }

  @Test
  void shouldPreferNodeWithCachedImageDigest() {
    final AiRuntimeWorkload workload = pendingWorkload();
    AiRuntimeNode uncached = readyNode();
    uncached.setNodeId("node-a");
    uncached.setCachedImageDigestsJson("[]");
    AiRuntimeNode cached = readyNode();
    cached.setNodeId("node-b");
    cached.setInstanceId("instance-b");
    cached.setCachedImageDigestsJson(
        "[\"sha256:" + "a".repeat(64) + "\"]"
    );
    when(workloadRepository.findPendingForUpdate(any()))
        .thenReturn(List.of(workload));
    when(nodeRepository.findSchedulableNodesForUpdate(any()))
        .thenReturn(List.of(uncached, cached));
    when(leaseRepository.findByNodeAndStatus(any(), any()))
        .thenReturn(List.of());

    assertEquals(1, coordinator.assignPending());

    assertEquals("node-b", workload.getAssignedNodeId());
  }

  @Test
  void shouldSpreadManagedPoolReplicasAcrossNodesBeforeCachePreference() {
    AiRuntimeWorkload first = pendingWorkload();
    first.setPoolId("pool-1");
    AiRuntimeWorkload second = pendingWorkload();
    second.setId("workload-2");
    second.setExecutionId("execution-2");
    second.setPoolId("pool-1");
    AiRuntimeNode cached = readyNode();
    cached.setCachedImageDigestsJson(
        "[\"sha256:" + "a".repeat(64) + "\"]"
    );
    AiRuntimeNode uncached = readyNode();
    uncached.setNodeId("node-b");
    uncached.setInstanceId("instance-b");
    uncached.setAdvertiseUrl("http://runtime-b:2891");
    when(workloadRepository.findPendingForUpdate(any()))
        .thenReturn(List.of(first, second));
    when(nodeRepository.findSchedulableNodesForUpdate(any()))
        .thenReturn(List.of(cached, uncached));
    when(leaseRepository.findByNodeAndStatus(any(), any()))
        .thenReturn(List.of());

    assertEquals(2, coordinator.assignPending());

    assertEquals("node-a", first.getAssignedNodeId());
    assertEquals("node-b", second.getAssignedNodeId());
  }

  @Test
  void shouldLeaveWorkloadPendingWhenPerWorkloadLimitDoesNotFit() {
    AiRuntimeWorkload workload = pendingWorkload();
    workload.setRequestedMemoryBytes(3L * 1024 * 1024 * 1024);
    AiRuntimeNode node = readyNode();
    when(workloadRepository.findPendingForUpdate(any()))
        .thenReturn(List.of(workload));
    when(nodeRepository.findSchedulableNodesForUpdate(any()))
        .thenReturn(List.of(node));
    when(leaseRepository.findByNodeAndStatus(
        "node-a",
        RuntimeLeaseStatus.ACTIVE
    )).thenReturn(List.of());

    int assigned = coordinator.assignPending();

    assertEquals(0, assigned);
    assertEquals(RuntimeWorkloadStatus.PENDING, workload.getStatus());
    assertNull(workload.getLeaseId());
  }

  @Test
  void shouldExpireAndReassignWithHigherFence() {
    AiRuntimeWorkload workload = pendingWorkload();
    workload.setStatus(RuntimeWorkloadStatus.RUNNING);
    workload.setAssignedNodeId("node-a");
    workload.setLeaseId("lease-old");
    workload.setFencingToken(1);
    AiRuntimeLease expired = lease(
        "lease-old",
        workload.getId(),
        1,
        Instant.now().minusSeconds(1)
    );
    when(leaseRepository.findExpiredActiveLeases(
        eq(RuntimeLeaseStatus.ACTIVE),
        any()
    )).thenReturn(List.of(expired));
    when(workloadRepository.findActiveByIdForUpdate(workload.getId()))
        .thenReturn(Optional.of(workload));

    assertEquals(1, coordinator.expireLeases());
    assertEquals(RuntimeWorkloadStatus.PENDING, workload.getStatus());
    assertEquals(1L, workload.getFencingToken());
    assertNull(workload.getLeaseId());

    when(workloadRepository.findPendingForUpdate(any()))
        .thenReturn(List.of(workload));
    when(nodeRepository.findSchedulableNodesForUpdate(any()))
        .thenReturn(List.of(readyNode()));
    when(leaseRepository.findByNodeAndStatus(
        "node-a",
        RuntimeLeaseStatus.ACTIVE
    )).thenReturn(List.of());

    assertEquals(1, coordinator.assignPending());
    assertEquals(2L, workload.getFencingToken());
    assertEquals("lease-1", workload.getLeaseId());
  }

  @Test
  void shouldIgnoreStaleDispatchConfirmation() {
    AiRuntimeWorkload workload = pendingWorkload();
    workload.setStatus(RuntimeWorkloadStatus.RUNNING);
    workload.setAssignedNodeId("node-a");
    workload.setLeaseId("lease-new");
    workload.setFencingToken(2);
    when(workloadRepository.findActiveByIdForUpdate(workload.getId()))
        .thenReturn(Optional.of(workload));
    DispatchTask stale = new DispatchTask(
        workload.getId(),
        "lease-old",
        1,
        "http://runtime-a:2891",
        null
    );
    RuntimeWorkloadObservation observation =
        new RuntimeWorkloadObservation(
            workload.getId(),
            "lease-old",
            1,
            workload.getExecutionId(),
            "",
            "container-old",
            workload.getImageReference() + "@" + workload.getImageDigest(),
            "exited",
            null,
            Instant.now().minusSeconds(5),
            Instant.now(),
            0,
            workload.getDeadlineAt(),
            "node-a"
        );

    assertFalse(coordinator.confirmStarted(stale, observation));
    assertEquals(RuntimeWorkloadStatus.RUNNING, workload.getStatus());
    assertEquals("lease-new", workload.getLeaseId());
  }

  @Test
  void shouldMapSuccessfulExitAndReleaseLease() {
    AiRuntimeWorkload workload = pendingWorkload();
    workload.setStatus(RuntimeWorkloadStatus.STARTING);
    workload.setAssignedNodeId("node-a");
    workload.setLeaseId("lease-1");
    workload.setFencingToken(1);
    AiRuntimeLease lease = lease(
        "lease-1",
        workload.getId(),
        1,
        Instant.now().plusSeconds(30)
    );
    when(workloadRepository.findActiveByIdForUpdate(workload.getId()))
        .thenReturn(Optional.of(workload));
    when(leaseRepository.findActiveByWorkloadForUpdate(workload.getId()))
        .thenReturn(Optional.of(lease));
    DispatchTask task = new DispatchTask(
        workload.getId(),
        "lease-1",
        1,
        "http://runtime-a:2891",
        null
    );
    RuntimeWorkloadObservation observation =
        new RuntimeWorkloadObservation(
            workload.getId(),
            "lease-1",
            1,
            workload.getExecutionId(),
            "",
            "container-a",
            workload.getImageReference() + "@" + workload.getImageDigest(),
            "exited",
            null,
            Instant.now().minusSeconds(1),
            Instant.now(),
            0,
            workload.getDeadlineAt(),
            "node-a"
        );

    assertTrue(coordinator.confirmStarted(task, observation));
    assertEquals(RuntimeWorkloadStatus.SUCCEEDED, workload.getStatus());
    assertEquals(RuntimeLeaseStatus.RELEASED, lease.getStatus());
  }

  private AiRuntimeWorkload pendingWorkload() {
    AiRuntimeWorkload workload = new AiRuntimeWorkload();
    workload.setId("workload-1");
    workload.setScopeType(AiResourceScope.SYSTEM);
    workload.setExecutionId("execution-1");
    workload.setServerId("server-1");
    workload.setImageReference("somesimpled/tool");
    workload.setImageDigest(
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    );
    workload.setRequestedMemoryBytes(256L * 1024 * 1024);
    workload.setRequestedNanoCpus(500_000_000L);
    workload.setRequestedPidsLimit(128);
    workload.setTimeoutSeconds(300);
    workload.setNetworkMode("none");
    workload.setEgressAllowlistJson("[]");
    workload.setSecretReferencesJson("[]");
    workload.setStatus(RuntimeWorkloadStatus.PENDING);
    workload.setDeadlineAt(Instant.now().plusSeconds(300));
    return workload;
  }

  private AiRuntimeNode readyNode() {
    AiRuntimeNode node = new AiRuntimeNode();
    node.setNodeId("node-a");
    node.setInstanceId("instance-a");
    node.setStatus(RuntimeNodeStatus.READY);
    node.setAdvertiseUrl("http://runtime-a:2891");
    node.setHeartbeatExpiresAt(Instant.now().plusSeconds(30));
    node.setCpuCores(8);
    node.setMemoryBytes(16L * 1024 * 1024 * 1024);
    node.setMaxWorkloads(32);
    node.setMaxWorkloadMemoryBytes(2L * 1024 * 1024 * 1024);
    node.setMaxWorkloadNanoCpus(4_000_000_000L);
    node.setMaxWorkloadPidsLimit(512);
    node.setCachedImageDigestsJson("[]");
    return node;
  }

  private AiRuntimeLease lease(
      final String id,
      final String workloadId,
      final long fencingToken,
      final Instant expiresAt
  ) {
    AiRuntimeLease lease = new AiRuntimeLease();
    lease.setId(id);
    lease.setWorkloadId(workloadId);
    lease.setNodeId("node-a");
    lease.setNodeInstanceId("instance-a");
    lease.setFencingToken(fencingToken);
    lease.setStatus(RuntimeLeaseStatus.ACTIVE);
    lease.setAcquiredAt(Instant.now().minusSeconds(10));
    lease.setRenewedAt(Instant.now().minusSeconds(10));
    lease.setExpiresAt(expiresAt);
    return lease;
  }
}
