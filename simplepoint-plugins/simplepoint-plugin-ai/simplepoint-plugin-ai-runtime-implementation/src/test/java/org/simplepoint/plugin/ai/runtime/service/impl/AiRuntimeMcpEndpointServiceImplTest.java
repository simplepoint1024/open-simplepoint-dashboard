package org.simplepoint.plugin.ai.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpEndpoint;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimePoolRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeWorkloadRepository;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeProfileCodec;
import org.simplepoint.plugin.ai.runtime.service.support.ManagedMcpSessionDirectory;

class AiRuntimeMcpEndpointServiceImplTest {

  private final AiRuntimePoolRepository poolRepository =
      mock(AiRuntimePoolRepository.class);

  private final AiRuntimeWorkloadRepository workloadRepository =
      mock(AiRuntimeWorkloadRepository.class);

  private final AiRuntimeNodeRepository nodeRepository =
      mock(AiRuntimeNodeRepository.class);

  private final AiRuntimeProperties properties = new AiRuntimeProperties();

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final TestSessionDirectory sessionDirectory =
      new TestSessionDirectory();

  private AiRuntimeMcpEndpointServiceImpl service;

  private AiRuntimePool pool;

  @BeforeEach
  void setUp() {
    properties.setMcpActivationTimeout(Duration.ofSeconds(1));
    service = new AiRuntimeMcpEndpointServiceImpl(
        poolRepository,
        workloadRepository,
        nodeRepository,
        properties,
        sessionDirectory,
        new AiRuntimeProfileCodec(objectMapper)
    );
    pool = pool();
    when(poolRepository.findActiveByServerAndScope(
        "server-1",
        AiResourceScope.SYSTEM,
        null
    )).thenReturn(Optional.of(pool));
    when(poolRepository.save(any(AiRuntimePool.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void resolvesReadyReplicaWithLeaseFenceAndTouchesPool() {
    when(workloadRepository.findActiveByPool("pool-1"))
        .thenReturn(List.of(workload()));
    when(nodeRepository.findActiveByNodeId("node-1"))
        .thenReturn(Optional.of(node()));

    RuntimeMcpEndpoint endpoint = service.resolve(
        "server-1",
        AiResourceScope.SYSTEM,
        null
    );

    assertThat(endpoint.endpointUrl())
        .isEqualTo("https://runtime-1:2891/mcp/v1/workloads/runtime-workload-1");
    assertThat(endpoint.leaseId()).isEqualTo("lease-1");
    assertThat(endpoint.fencingToken()).isEqualTo(9);
    verify(poolRepository).save(pool);
  }

  @Test
  void activatesScaleToZeroPoolBeforeTimingOut() {
    properties.setMcpActivationTimeout(Duration.ofMillis(10));
    when(workloadRepository.findActiveByPool("pool-1")).thenReturn(List.of());

    assertThatThrownBy(() -> service.resolve(
        "server-1",
        AiResourceScope.SYSTEM,
        null
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ready replica");

    assertThat(pool.getDesiredReplicas()).isEqualTo(2);
    assertThat(pool.getStatus()).isEqualTo(RuntimePoolStatus.SCALING);
    verify(poolRepository).save(pool);
  }

  @Test
  void distributesNewSessionsAndKeepsEachSessionOnItsReplica() {
    AiRuntimeWorkload first = workload("workload-1", "node-1", "lease-1", 9, 1);
    AiRuntimeWorkload second = workload("workload-2", "node-2", "lease-2", 10, 2);
    when(workloadRepository.findActiveByPool("pool-1"))
        .thenReturn(List.of(first, second));
    when(nodeRepository.findActiveByNodeId("node-1"))
        .thenReturn(Optional.of(node("node-1", "runtime-1")));
    when(nodeRepository.findActiveByNodeId("node-2"))
        .thenReturn(Optional.of(node("node-2", "runtime-2")));

    Set<String> selectedWorkloads = new HashSet<>();
    for (int index = 0; index < 128; index++) {
      selectedWorkloads.add(service.resolve(
          "server-1",
          AiResourceScope.SYSTEM,
          null,
          "session-" + index
      ).workloadId());
    }

    RuntimeMcpEndpoint firstResolution = service.resolve(
        "server-1",
        AiResourceScope.SYSTEM,
        null,
        "stable-session"
    );
    RuntimeMcpEndpoint secondResolution = service.resolve(
        "server-1",
        AiResourceScope.SYSTEM,
        null,
        "stable-session"
    );

    assertThat(selectedWorkloads).containsExactlyInAnyOrder(
        "workload-1",
        "workload-2"
    );
    assertThat(secondResolution).isEqualTo(firstResolution);
  }

  @Test
  void reservesExactlyOneWorkloadForEachDedicatedSession()
      throws JsonProcessingException {
    pool.setRuntimeSpecJson(objectMapper.writeValueAsString(profile(
        RuntimeMcpProfileSpec.SessionMode.DEDICATED,
        1
    )));
    AiRuntimeWorkload first = workload("workload-1", "node-1", "lease-1", 9, 1);
    AiRuntimeWorkload second = workload("workload-2", "node-2", "lease-2", 10, 2);
    when(workloadRepository.findActiveByPool("pool-1"))
        .thenReturn(List.of(first, second));
    when(nodeRepository.findActiveByNodeId("node-1"))
        .thenReturn(Optional.of(node("node-1", "runtime-1")));
    when(nodeRepository.findActiveByNodeId("node-2"))
        .thenReturn(Optional.of(node("node-2", "runtime-2")));

    RuntimeMcpEndpoint firstSession = service.resolve(
        "server-1", AiResourceScope.SYSTEM, null, "dedicated-a"
    );
    RuntimeMcpEndpoint secondSession = service.resolve(
        "server-1", AiResourceScope.SYSTEM, null, "dedicated-b"
    );
    RuntimeMcpEndpoint repeated = service.resolve(
        "server-1", AiResourceScope.SYSTEM, null, "dedicated-a"
    );

    assertThat(firstSession.workloadId())
        .isNotEqualTo(secondSession.workloadId());
    assertThat(firstSession.sessionAssignmentKey()).isNotBlank();
    assertThat(repeated.workloadId()).isEqualTo(firstSession.workloadId());
  }

  @Test
  void poolAffineHonorsDeclaredSessionCapacity()
      throws JsonProcessingException {
    properties.setMcpActivationTimeout(Duration.ofMillis(10));
    pool.setMaxReplicas(1);
    pool.setDesiredReplicas(1);
    pool.setRuntimeSpecJson(objectMapper.writeValueAsString(profile(
        RuntimeMcpProfileSpec.SessionMode.POOL_AFFINE,
        2
    )));
    when(workloadRepository.findActiveByPool("pool-1"))
        .thenReturn(List.of(workload()));
    when(nodeRepository.findActiveByNodeId("node-1"))
        .thenReturn(Optional.of(node()));

    service.resolve("server-1", AiResourceScope.SYSTEM, null, "affine-a");
    service.resolve("server-1", AiResourceScope.SYSTEM, null, "affine-b");

    assertThatThrownBy(() -> service.resolve(
        "server-1", AiResourceScope.SYSTEM, null, "affine-c"
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ready replica");
  }

  @Test
  void reassignsStoredSessionWhenItsNodeExpires() {
    AiRuntimeWorkload first = workload("workload-1", "node-1", "lease-1", 9, 1);
    AiRuntimeWorkload second = workload("workload-2", "node-2", "lease-2", 10, 2);
    AiRuntimeNode firstNode = node("node-1", "runtime-1");
    AiRuntimeNode secondNode = node("node-2", "runtime-2");
    when(workloadRepository.findActiveByPool("pool-1"))
        .thenReturn(List.of(first, second));
    when(nodeRepository.findActiveByNodeId("node-1"))
        .thenReturn(Optional.of(firstNode));
    when(nodeRepository.findActiveByNodeId("node-2"))
        .thenReturn(Optional.of(secondNode));

    RuntimeMcpEndpoint original = service.resolve(
        "server-1",
        AiResourceScope.SYSTEM,
        null,
        "failover-session"
    );
    if ("workload-1".equals(original.workloadId())) {
      firstNode.setHeartbeatExpiresAt(Instant.now().minusSeconds(1));
    } else {
      secondNode.setHeartbeatExpiresAt(Instant.now().minusSeconds(1));
    }

    RuntimeMcpEndpoint replacement = service.resolve(
        "server-1",
        AiResourceScope.SYSTEM,
        null,
        "failover-session"
    );

    assertThat(replacement.workloadId())
        .isNotEqualTo(original.workloadId());
  }

  @Test
  void quarantinesConnectionFailureAndMovesSession() {
    AiRuntimeWorkload first = workload("workload-1", "node-1", "lease-1", 9, 1);
    AiRuntimeWorkload second = workload("workload-2", "node-2", "lease-2", 10, 2);
    when(workloadRepository.findActiveByPool("pool-1"))
        .thenReturn(List.of(first, second));
    when(nodeRepository.findActiveByNodeId("node-1"))
        .thenReturn(Optional.of(node("node-1", "runtime-1")));
    when(nodeRepository.findActiveByNodeId("node-2"))
        .thenReturn(Optional.of(node("node-2", "runtime-2")));

    RuntimeMcpEndpoint original = service.resolve(
        "server-1",
        AiResourceScope.SYSTEM,
        null,
        "connection-failure-session"
    );
    service.invalidate(
        "server-1",
        AiResourceScope.SYSTEM,
        null,
        "connection-failure-session",
        original
    );
    RuntimeMcpEndpoint replacement = service.resolve(
        "server-1",
        AiResourceScope.SYSTEM,
        null,
        "connection-failure-session"
    );

    assertThat(replacement.workloadId())
        .isNotEqualTo(original.workloadId());
  }

  private static AiRuntimePool pool() {
    AiRuntimePool value = new AiRuntimePool();
    value.setId("pool-1");
    value.setScopeType(AiResourceScope.SYSTEM);
    value.setServerId("server-1");
    value.setMinReplicas(0);
    value.setMaxReplicas(4);
    value.setDesiredReplicas(0);
    value.setActivationReplicas(2);
    value.setStatus(RuntimePoolStatus.IDLE);
    value.setLastActivityAt(Instant.now().minusSeconds(60));
    return value;
  }

  private static AiRuntimeWorkload workload() {
    return workload("runtime-workload-1", "node-1", "lease-1", 9, 1);
  }

  private static AiRuntimeWorkload workload(
      final String workloadId,
      final String nodeId,
      final String leaseId,
      final long fencingToken,
      final long replicaSequence
  ) {
    AiRuntimeWorkload value = new AiRuntimeWorkload();
    value.setScopeType(AiResourceScope.SYSTEM);
    value.setServerId("server-1");
    value.setPoolId("pool-1");
    value.setReplicaSequence(replicaSequence);
    value.setStatus(RuntimeWorkloadStatus.RUNNING);
    value.setAssignedNodeId(nodeId);
    value.setRuntimeWorkloadId(workloadId);
    value.setLeaseId(leaseId);
    value.setFencingToken(fencingToken);
    return value;
  }

  private static RuntimeMcpProfileSpec profile(
      final RuntimeMcpProfileSpec.SessionMode mode,
      final int maximumSessions
  ) {
    return new RuntimeMcpProfileSpec(
        "io.example/server",
        "1.0.0",
        new RuntimeMcpProfileSpec.Artifact(
            RuntimeMcpProfileSpec.ArtifactSource.UPSTREAM_ORIGINAL,
            RuntimeMcpProfileSpec.ArtifactType.OCI,
            "ghcr.io/example/server@sha256:" + "a".repeat(64)
        ),
        new RuntimeMcpProfileSpec.Transport(
            RuntimeMcpProfileSpec.TransportType.STDIO,
            null,
            null
        ),
        new RuntimeMcpProfileSpec.Process(List.of(), List.of(), List.of(), null),
        List.of(),
        List.of(),
        List.of(),
        new RuntimeMcpProfileSpec.NetworkPolicy(
            RuntimeMcpProfileSpec.NetworkMode.NONE,
            List.of()
        ),
        new RuntimeMcpProfileSpec.SessionPolicy(mode, maximumSessions),
        new RuntimeMcpProfileSpec.SandboxPolicy(
            RuntimeMcpProfileSpec.SandboxProfile.STRICT,
            Map.of()
        )
    );
  }

  private static AiRuntimeNode node() {
    return node("node-1", "runtime-1");
  }

  private static AiRuntimeNode node(
      final String nodeId,
      final String host
  ) {
    AiRuntimeNode value = new AiRuntimeNode();
    value.setNodeId(nodeId);
    value.setAdvertiseUrl("https://" + host + ":2891");
    value.setStatus(RuntimeNodeStatus.READY);
    value.setHeartbeatExpiresAt(Instant.now().plusSeconds(30));
    return value;
  }

  private static final class TestSessionDirectory
      implements ManagedMcpSessionDirectory {

    private final Map<String, Assignment> assignments =
        new ConcurrentHashMap<>();

    private final Set<Assignment> quarantined = ConcurrentHashMap.newKeySet();

    private final Map<Assignment, Set<String>> capacity =
        new ConcurrentHashMap<>();

    @Override
    public String sessionKey(
        final String serverId,
        final String scope,
        final String tenantId,
        final String sessionId
    ) {
      return serverId + ":" + scope + ":" + sessionId.hashCode();
    }

    @Override
    public Optional<Assignment> find(final String sessionKey) {
      return Optional.ofNullable(assignments.get(sessionKey));
    }

    @Override
    public Assignment claim(
        final String sessionKey,
        final Assignment candidate
    ) {
      return assignments.computeIfAbsent(sessionKey, ignored -> candidate);
    }

    @Override
    public synchronized Optional<Assignment> claimAvailable(
        final String sessionKey,
        final Assignment candidate,
        final int maximumSessions
    ) {
      Assignment existing = assignments.get(sessionKey);
      if (existing != null) {
        return Optional.of(existing);
      }
      Set<String> occupied = capacity.computeIfAbsent(
          candidate,
          ignored -> ConcurrentHashMap.newKeySet()
      );
      if (occupied.size() >= maximumSessions) {
        return Optional.empty();
      }
      assignments.put(sessionKey, candidate);
      occupied.add(sessionKey);
      return Optional.of(candidate);
    }

    @Override
    public void touch(
        final String sessionKey,
        final Assignment assignment
    ) {
      // In-memory tests do not expire assignments.
    }

    @Override
    public void invalidate(
        final String sessionKey,
        final Assignment assignment
    ) {
      if (assignments.remove(sessionKey, assignment)) {
        Set<String> occupied = capacity.get(assignment);
        if (occupied != null) {
          occupied.remove(sessionKey);
        }
      }
    }

    @Override
    public synchronized void invalidateWorkload(
        final Assignment assignment
    ) {
      Set<String> occupied = capacity.remove(assignment);
      if (occupied != null) {
        occupied.forEach(sessionKey ->
            assignments.remove(sessionKey, assignment));
      }
    }

    @Override
    public void quarantine(
        final String serverId,
        final Assignment assignment
    ) {
      quarantined.add(assignment);
    }

    @Override
    public boolean isQuarantined(
        final String serverId,
        final Assignment assignment
    ) {
      return quarantined.contains(assignment);
    }

    @Override
    public Duration assignmentTtl() {
      return Duration.ofMinutes(10);
    }
  }
}
