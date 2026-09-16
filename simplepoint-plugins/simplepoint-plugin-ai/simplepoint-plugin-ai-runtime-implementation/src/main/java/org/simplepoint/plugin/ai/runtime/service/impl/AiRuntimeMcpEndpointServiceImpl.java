package org.simplepoint.plugin.ai.runtime.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
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
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeMcpEndpointService;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeProfileCodec;
import org.simplepoint.plugin.ai.runtime.service.support.ManagedMcpSessionDirectory;
import org.simplepoint.plugin.ai.runtime.service.support.ManagedMcpSessionDirectory.Assignment;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Activates a managed pool and resolves one fenced Runtime stdio bridge.
 */
@Service
public class AiRuntimeMcpEndpointServiceImpl
    implements AiRuntimeMcpEndpointService {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

  private final AiRuntimePoolRepository poolRepository;

  private final AiRuntimeWorkloadRepository workloadRepository;

  private final AiRuntimeNodeRepository nodeRepository;

  private final AiRuntimeProperties properties;

  private final ManagedMcpSessionDirectory sessionDirectory;

  private final AiRuntimeProfileCodec profileCodec;

  private final AtomicLong unboundSequence = new AtomicLong(System.nanoTime());

  /**
   * Creates the managed MCP endpoint resolver.
   */
  public AiRuntimeMcpEndpointServiceImpl(
      final AiRuntimePoolRepository poolRepository,
      final AiRuntimeWorkloadRepository workloadRepository,
      final AiRuntimeNodeRepository nodeRepository,
      final AiRuntimeProperties properties,
      final ManagedMcpSessionDirectory sessionDirectory,
      final AiRuntimeProfileCodec profileCodec
  ) {
    this.poolRepository = poolRepository;
    this.workloadRepository = workloadRepository;
    this.nodeRepository = nodeRepository;
    this.properties = properties;
    this.sessionDirectory = sessionDirectory;
    this.profileCodec = profileCodec;
  }

  @Override
  public RuntimeMcpEndpoint resolve(
      final String serverId,
      final AiResourceScope scopeType,
      final String tenantId,
      final String sessionId
  ) {
    String normalizedServerId = required(serverId, "MCP server ID");
    if (scopeType == null) {
      throw new IllegalArgumentException("MCP server scope is required");
    }
    Duration timeout = properties.getMcpActivationTimeout();
    if (timeout == null || timeout.isNegative() || timeout.isZero()
        || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalStateException("Runtime MCP activation timeout is invalid");
    }
    Instant deadline = Instant.now().plus(timeout);
    boolean activated = false;
    do {
      AiRuntimePool pool = requirePool(normalizedServerId, scopeType, tenantId);
      Optional<RuntimeMcpEndpoint> endpoint = selectEndpoint(pool, sessionId);
      if (endpoint.isPresent()) {
        touch(pool);
        return endpoint.get();
      }
      if (!activated) {
        activate(pool);
        activated = true;
      }
      if (Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Runtime MCP activation was interrupted");
      }
      LockSupport.parkNanos(POLL_INTERVAL.toNanos());
    } while (Instant.now().isBefore(deadline));
    throw new IllegalStateException(
        "Managed OCI MCP pool did not produce a ready replica before timeout"
    );
  }

  @Override
  public void invalidate(
      final String serverId,
      final AiResourceScope scopeType,
      final String tenantId,
      final String sessionId,
      final RuntimeMcpEndpoint endpoint
  ) {
    String normalizedServerId = required(serverId, "MCP server ID");
    if (scopeType == null) {
      throw new IllegalArgumentException("MCP server scope is required");
    }
    Assignment assignment = assignment(endpoint);
    sessionDirectory.quarantine(normalizedServerId, assignment);
    String sessionKey = endpoint == null
        ? null : endpoint.sessionAssignmentKey();
    if ((sessionKey == null || sessionKey.isBlank())
        && sessionId != null && !sessionId.isBlank()) {
      sessionKey = sessionDirectory.sessionKey(
          normalizedServerId,
          scopeType.name(),
          tenantId,
          sessionId
      );
    }
    if (sessionKey != null && !sessionKey.isBlank()) {
      sessionDirectory.invalidate(sessionKey, assignment);
    }
  }

  private AiRuntimePool requirePool(
      final String serverId,
      final AiResourceScope scopeType,
      final String tenantId
  ) {
    AiRuntimePool pool = poolRepository.findActiveByServerAndScope(
        serverId,
        scopeType,
        tenantId
    ).orElseThrow(() -> new IllegalStateException(
        "Managed OCI MCP server has no Runtime pool"
    ));
    if (pool.getStatus() == RuntimePoolStatus.DISABLED) {
      throw new IllegalStateException("Managed OCI MCP Runtime pool is disabled");
    }
    return pool;
  }

  private Optional<RuntimeMcpEndpoint> selectEndpoint(
      final AiRuntimePool pool,
      final String sessionId
  ) {
    List<RuntimeMcpEndpoint> candidates = readyEndpoints(pool).stream()
        .filter(endpoint -> !sessionDirectory.isQuarantined(
            pool.getServerId(),
            assignment(endpoint)
        ))
        .toList();
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    SessionPolicy policy = sessionPolicy(pool);
    if (policy.mode()
        == RuntimeMcpProfileSpec.SessionMode.SHARED_STATELESS) {
      if (sessionId != null && !sessionId.isBlank()) {
        String routingKey = sessionDirectory.sessionKey(
            pool.getServerId(),
            pool.getScopeType().name(),
            pool.getTenantId(),
            sessionId
        );
        return Optional.of(rendezvous(routingKey, candidates));
      }
      int selected = Math.floorMod(
          unboundSequence.getAndIncrement(),
          candidates.size()
      );
      return Optional.of(candidates.get(selected));
    }
    String routingSessionId = sessionId == null || sessionId.isBlank()
        ? "unbound-" + Long.toUnsignedString(unboundSequence.getAndIncrement())
        : sessionId;
    String sessionKey = sessionDirectory.sessionKey(
        pool.getServerId(),
        pool.getScopeType().name(),
        pool.getTenantId(),
        routingSessionId
    );
    for (int attempt = 0; attempt < 3; attempt++) {
      Optional<Assignment> existing = sessionDirectory.find(sessionKey);
      if (existing.isPresent()) {
        Optional<RuntimeMcpEndpoint> assigned = matching(
            candidates,
            existing.get()
        );
        if (assigned.isPresent()) {
          sessionDirectory.touch(sessionKey, existing.get());
          return assigned.map(endpoint -> reserved(endpoint, sessionKey));
        }
        sessionDirectory.invalidate(sessionKey, existing.get());
      }
      List<RuntimeMcpEndpoint> ordered = candidates.stream()
          .sorted((left, right) -> compareUnsigned(
              score(sessionKey, right),
              score(sessionKey, left)
          ))
          .toList();
      for (RuntimeMcpEndpoint selected : ordered) {
        Optional<Assignment> claimed = sessionDirectory.claimAvailable(
            sessionKey,
            assignment(selected),
            policy.maximumSessions()
        );
        if (claimed.isEmpty()) {
          continue;
        }
        Optional<RuntimeMcpEndpoint> assigned = matching(
            candidates, claimed.get()
        );
        if (assigned.isPresent()) {
          sessionDirectory.touch(sessionKey, claimed.get());
          return assigned.map(endpoint -> reserved(endpoint, sessionKey));
        }
        sessionDirectory.invalidate(sessionKey, claimed.get());
      }
      return Optional.empty();
    }
    throw new IllegalStateException(
        "Managed OCI MCP session assignment did not stabilize"
    );
  }

  private SessionPolicy sessionPolicy(final AiRuntimePool pool) {
    if (pool.getRuntimeSpecJson() == null
        || pool.getRuntimeSpecJson().isBlank()) {
      return new SessionPolicy(
          RuntimeMcpProfileSpec.SessionMode.SHARED_STATELESS,
          256
      );
    }
    RuntimeMcpProfileSpec.SessionPolicy value = profileCodec.decode(
        pool.getRuntimeSpecJson()
    ).session();
    return new SessionPolicy(value.mode(), value.maxSessions());
  }

  private List<RuntimeMcpEndpoint> readyEndpoints(final AiRuntimePool pool) {
    Instant now = Instant.now();
    List<RuntimeMcpEndpoint> endpoints = new ArrayList<>();
    workloadRepository.findActiveByPool(pool.getId()).stream()
        .filter(workload -> workload.getStatus() == RuntimeWorkloadStatus.RUNNING)
        .filter(workload -> workload.getScopeType() == pool.getScopeType())
        .filter(workload -> Objects.equals(
            workload.getTenantId(),
            pool.getTenantId()
        ))
        .filter(workload -> pool.getServerId().equals(workload.getServerId()))
        .filter(AiRuntimeMcpEndpointServiceImpl::hasFence)
        .sorted(Comparator.comparing(
            AiRuntimeWorkload::getReplicaSequence,
            Comparator.nullsLast(Long::compareTo)
        ))
        .map(workload -> endpoint(workload, now))
        .flatMap(Optional::stream)
        .forEach(endpoints::add);
    return List.copyOf(endpoints);
  }

  private Optional<RuntimeMcpEndpoint> endpoint(
      final AiRuntimeWorkload workload,
      final Instant now
  ) {
    Optional<AiRuntimeNode> candidate = nodeRepository.findActiveByNodeId(
        workload.getAssignedNodeId()
    );
    if (candidate.isEmpty()) {
      return Optional.empty();
    }
    AiRuntimeNode node = candidate.get();
    if (node.getStatus() != RuntimeNodeStatus.READY
        || node.getHeartbeatExpiresAt().isBefore(now)) {
      return Optional.empty();
    }
    String base = node.getAdvertiseUrl().endsWith("/")
        ? node.getAdvertiseUrl().substring(0, node.getAdvertiseUrl().length() - 1)
        : node.getAdvertiseUrl();
    return Optional.of(new RuntimeMcpEndpoint(
        base + "/mcp/v1/workloads/" + workload.getRuntimeWorkloadId(),
        workload.getRuntimeWorkloadId(),
        workload.getLeaseId(),
        workload.getFencingToken(),
        null
    ));
  }

  private static RuntimeMcpEndpoint reserved(
      final RuntimeMcpEndpoint endpoint,
      final String sessionKey
  ) {
    return new RuntimeMcpEndpoint(
        endpoint.endpointUrl(),
        endpoint.workloadId(),
        endpoint.leaseId(),
        endpoint.fencingToken(),
        sessionKey
    );
  }

  private static Optional<RuntimeMcpEndpoint> matching(
      final List<RuntimeMcpEndpoint> candidates,
      final Assignment assignment
  ) {
    return candidates.stream()
        .filter(candidate -> assignment(candidate).equals(assignment))
        .findFirst();
  }

  private static RuntimeMcpEndpoint rendezvous(
      final String sessionKey,
      final List<RuntimeMcpEndpoint> candidates
  ) {
    return candidates.stream()
        .max((left, right) -> compareUnsigned(
            score(sessionKey, left),
            score(sessionKey, right)
        ))
        .orElseThrow();
  }

  private static byte[] score(
      final String sessionKey,
      final RuntimeMcpEndpoint endpoint
  ) {
    Assignment assignment = assignment(endpoint);
    String material = sessionKey
        + '\0' + assignment.workloadId()
        + '\0' + assignment.leaseId()
        + '\0' + assignment.fencingToken();
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(material.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static int compareUnsigned(
      final byte[] left,
      final byte[] right
  ) {
    return Arrays.compareUnsigned(left, right);
  }

  private static Assignment assignment(final RuntimeMcpEndpoint endpoint) {
    if (endpoint == null) {
      throw new IllegalArgumentException("Runtime MCP endpoint is required");
    }
    return new Assignment(
        required(endpoint.workloadId(), "Runtime workload ID"),
        required(endpoint.leaseId(), "Runtime lease ID"),
        endpoint.fencingToken()
    );
  }

  private void activate(final AiRuntimePool pool) {
    int activationTarget = Math.max(
        pool.getMinReplicas(), pool.getActivationReplicas()
    );
    int target = Math.min(pool.getMaxReplicas(), Math.max(
        activationTarget,
        pool.getDesiredReplicas() + 1
    ));
    if (target <= 0) {
      throw new IllegalStateException(
          "Managed OCI MCP Runtime pool activation capacity is zero"
      );
    }
    pool.setDesiredReplicas(Math.max(pool.getDesiredReplicas(), target));
    pool.setLastActivityAt(Instant.now());
    pool.setStatus(RuntimePoolStatus.SCALING);
    pool.setLastError(null);
    saveBestEffort(pool);
  }

  private void touch(final AiRuntimePool pool) {
    pool.setLastActivityAt(Instant.now());
    saveBestEffort(pool);
  }

  private void saveBestEffort(final AiRuntimePool pool) {
    try {
      poolRepository.save(pool);
    } catch (OptimisticLockingFailureException ignored) {
      // The coordinator owns reconciliation; the next request refreshes activity.
    }
  }

  private static boolean hasFence(final AiRuntimeWorkload workload) {
    return workload.getRuntimeWorkloadId() != null
        && !workload.getRuntimeWorkloadId().isBlank()
        && workload.getAssignedNodeId() != null
        && !workload.getAssignedNodeId().isBlank()
        && workload.getLeaseId() != null
        && !workload.getLeaseId().isBlank()
        && workload.getFencingToken() > 0;
  }

  private static String required(final String value, final String name) {
    String normalized = value == null ? null : value.trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }

  private record SessionPolicy(
      RuntimeMcpProfileSpec.SessionMode mode,
      int maximumSessions
  ) {
  }
}
