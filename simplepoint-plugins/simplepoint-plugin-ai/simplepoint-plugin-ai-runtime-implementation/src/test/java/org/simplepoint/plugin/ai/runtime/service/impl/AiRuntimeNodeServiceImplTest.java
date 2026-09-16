package org.simplepoint.plugin.ai.runtime.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeFencedException;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeHeartbeat;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeRegistration;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeImageCacheCodec;

class AiRuntimeNodeServiceImplTest {

  private AiRuntimeNodeRepository repository;

  private AiRuntimeProperties properties;

  private AiRuntimeNodeServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = mock(AiRuntimeNodeRepository.class);
    properties = new AiRuntimeProperties();
    properties.setHeartbeatInterval(Duration.ofSeconds(10));
    properties.setHeartbeatTimeout(Duration.ofSeconds(35));
    service = new AiRuntimeNodeServiceImpl(
        repository,
        properties,
        new ObjectMapper(),
        new AiRuntimeImageCacheCodec(new ObjectMapper())
    );
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void shouldRegisterNewNodeAsFirstReadyGeneration() {
    when(repository.findActiveByNodeIdForUpdate("node-a"))
        .thenReturn(Optional.empty());

    final var response = service.register("node-a", registration("instance-a"));

    assertEquals(1L, response.generation());
    assertEquals(RuntimeNodeStatus.READY, response.status());
    assertEquals("instance-a", response.instanceId());
    assertTrue(response.heartbeatTimeoutSeconds()
        > response.heartbeatIntervalSeconds());
    verify(repository).save(any(AiRuntimeNode.class));
  }

  @Test
  void shouldFenceHeartbeatFromReplacedProcessGeneration() {
    final AiRuntimeNode node = existingNode("instance-old", 7L);
    when(repository.findActiveByNodeIdForUpdate("node-a"))
        .thenReturn(Optional.of(node));

    final var response = service.register("node-a", registration("instance-new"));

    assertEquals(8L, response.generation());
    assertThrows(
        RuntimeNodeFencedException.class,
        () -> service.heartbeat("node-a", heartbeat("instance-old"))
    );
  }

  @Test
  void shouldRenewCapacityAndHealthDuringHeartbeat() {
    final AiRuntimeNode node = existingNode("instance-a", 3L);
    when(repository.findActiveByNodeIdForUpdate("node-a"))
        .thenReturn(Optional.of(node));

    final var response = service.heartbeat("node-a", heartbeat("instance-a"));

    assertEquals(RuntimeNodeStatus.READY, response.status());
    assertEquals(4, node.getRunningWorkloads());
    assertNotNull(node.getHeartbeatExpiresAt());
    assertTrue(node.getHeartbeatExpiresAt().isAfter(node.getLastHeartbeatAt()));
  }

  @Test
  void shouldExpireStaleLiveNodes() {
    final AiRuntimeNode stale = existingNode("instance-a", 2L);
    stale.setStatus(RuntimeNodeStatus.ERROR);
    when(repository.findExpiredNodes(any(), any())).thenReturn(List.of(stale));

    final int count = service.expireStaleNodes();

    assertEquals(1, count);
    assertEquals(RuntimeNodeStatus.OFFLINE, stale.getStatus());
    assertEquals("Runtime node heartbeat lease expired", stale.getLastError());
    verify(repository).saveAll(List.of(stale));
  }

  @Test
  void shouldSkipStaleNodeQueryWhenRuntimeSchedulingIsDisabled() {
    properties.setSchedulingEnabled(false);

    final int count = service.expireStaleNodes();

    assertEquals(0, count);
    verifyNoInteractions(repository);
  }

  @Test
  void shouldRejectUnsafeAdvertiseUrlAndCapacity() {
    final RuntimeNodeRegistration unsafeUrl = new RuntimeNodeRegistration(
        "instance-a",
        "Docker A",
        "http://runtime-a:2891/private",
        "test",
        "1.51",
        "linux",
        8,
        1024L * 1024 * 1024,
        32,
        0,
        List.of(),
        2L * 1024 * 1024 * 1024,
        4_000_000_000L,
        512,
        true,
        true,
        false,
        false,
        false,
        Map.of()
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> service.register("node-a", unsafeUrl)
    );

    final RuntimeNodeRegistration invalidCapacity = new RuntimeNodeRegistration(
        "instance-a",
        "Docker A",
        "http://runtime-a:2891",
        "test",
        "1.51",
        "linux",
        8,
        1024L,
        32,
        0,
        List.of(),
        2L * 1024 * 1024 * 1024,
        4_000_000_000L,
        512,
        true,
        true,
        false,
        false,
        false,
        Map.of()
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> service.register("node-a", invalidCapacity)
    );
  }

  private RuntimeNodeRegistration registration(final String instanceId) {
    return new RuntimeNodeRegistration(
        instanceId,
        "Docker A",
        "http://runtime-a:2891",
        "test",
        "1.51",
        "linux",
        8,
        16L * 1024 * 1024 * 1024,
        32,
        4,
        List.of("sha256:" + "a".repeat(64)),
        2L * 1024 * 1024 * 1024,
        4_000_000_000L,
        512,
        true,
        true,
        false,
        false,
        true,
        Map.of("zone", "test-a")
    );
  }

  private RuntimeNodeHeartbeat heartbeat(final String instanceId) {
    return new RuntimeNodeHeartbeat(
        instanceId,
        "1.51",
        "linux",
        8,
        16L * 1024 * 1024 * 1024,
        32,
        4,
        List.of("sha256:" + "a".repeat(64)),
        2L * 1024 * 1024 * 1024,
        4_000_000_000L,
        512,
        true,
        null
    );
  }

  private AiRuntimeNode existingNode(
      final String instanceId,
      final long generation
  ) {
    final AiRuntimeNode node = new AiRuntimeNode();
    node.setNodeId("node-a");
    node.setInstanceId(instanceId);
    node.setGeneration(generation);
    node.setDisplayName("Docker A");
    node.setAdvertiseUrl("http://runtime-a:2891");
    node.setRuntimeVersion("test");
    node.setEngineApiVersion("1.51");
    node.setEngineOsType("linux");
    node.setCpuCores(8);
    node.setMemoryBytes(16L * 1024 * 1024 * 1024);
    node.setMaxWorkloads(32);
    node.setRunningWorkloads(4);
    node.setMaxWorkloadMemoryBytes(2L * 1024 * 1024 * 1024);
    node.setMaxWorkloadNanoCpus(4_000_000_000L);
    node.setMaxWorkloadPidsLimit(512);
    node.setLabelsJson("{}");
    node.setCachedImageDigestsJson("[]");
    node.setStatus(RuntimeNodeStatus.READY);
    node.setRegisteredAt(Instant.now().minusSeconds(60));
    node.setLastHeartbeatAt(Instant.now().minusSeconds(5));
    node.setHeartbeatExpiresAt(Instant.now().plusSeconds(30));
    return node;
  }
}
