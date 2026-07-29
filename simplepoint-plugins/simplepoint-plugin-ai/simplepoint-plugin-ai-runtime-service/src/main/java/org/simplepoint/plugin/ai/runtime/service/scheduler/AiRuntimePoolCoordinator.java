package org.simplepoint.plugin.ai.runtime.service.scheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageObservation;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimePoolRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeWorkloadRepository;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeImageCacheCodec;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short desired-state transactions for OCI image prewarming and replica pools.
 */
@Service
public class AiRuntimePoolCoordinator {

  private static final List<RuntimePoolStatus> RECONCILABLE =
      List.of(RuntimePoolStatus.values());

  private final AiRuntimePoolRepository poolRepository;

  private final AiRuntimeNodeRepository nodeRepository;

  private final AiRuntimeWorkloadRepository workloadRepository;

  private final AiRuntimeProperties properties;

  private final AiRuntimeImageCacheCodec imageCacheCodec;

  /**
   * Creates the transactional runtime pool coordinator.
   */
  public AiRuntimePoolCoordinator(
      final AiRuntimePoolRepository poolRepository,
      final AiRuntimeNodeRepository nodeRepository,
      final AiRuntimeWorkloadRepository workloadRepository,
      final AiRuntimeProperties properties,
      final AiRuntimeImageCacheCodec imageCacheCodec
  ) {
    this.poolRepository = poolRepository;
    this.nodeRepository = nodeRepository;
    this.workloadRepository = workloadRepository;
    this.properties = properties;
    this.imageCacheCodec = imageCacheCodec;
  }

  /**
   * Claims bounded prewarm calls while all durable decisions are fenced.
   */
  @Transactional(rollbackFor = Exception.class)
  public List<PrewarmTask> claimPrewarms() {
    Instant now = Instant.now();
    List<AiRuntimePool> pools = poolRepository.findReconcileForUpdate(
        RECONCILABLE,
        PageRequest.of(0, batchSize())
    );
    if (pools.isEmpty()) {
      return List.of();
    }
    List<AiRuntimeNode> nodes =
        nodeRepository.findSchedulableNodesForUpdate(now);
    List<PrewarmTask> tasks = new ArrayList<>();
    for (AiRuntimePool pool : pools) {
      int target = target(pool, now);
      List<AiRuntimeNode> capable = nodes.stream()
          .filter(node -> fits(node, pool))
          .toList();
      int cached = (int) capable.stream()
          .filter(node -> cached(node, pool.getImageDigest()))
          .count();
      pool.setPrewarmedNodes(cached);
      int required = Math.min(
          pool.getPrewarmNodes(),
          Math.min(target, capable.size())
      );
      if (pool.getStatus() == RuntimePoolStatus.DISABLED
          || required <= cached
          || !prewarmDue(pool, now)) {
        poolRepository.save(pool);
        continue;
      }
      int taskCount = tasks.size();
      capable.stream()
          .filter(node -> !cached(node, pool.getImageDigest()))
          .limit(required - cached)
          .map(node -> new PrewarmTask(
              pool.getId(),
              node.getNodeId(),
              node.getInstanceId(),
              node.getAdvertiseUrl(),
              pool.getImageReference() + "@" + pool.getImageDigest(),
              pool.getImageDigest()
          ))
          .forEach(tasks::add);
      if (tasks.size() > taskCount) {
        pool.setLastPrewarmAt(now);
      }
      poolRepository.save(pool);
    }
    return List.copyOf(tasks);
  }

  /**
   * Confirms a successful node-local pull and immediately updates cache
   * affinity without waiting for the next heartbeat.
   */
  @Transactional(rollbackFor = Exception.class)
  public void confirmPrewarm(
      final PrewarmTask task,
      final RuntimeImageObservation observation
  ) {
    if (task == null || observation == null || !observedDigest(
        observation,
        task.imageDigest()
    )) {
      throw new IllegalArgumentException(
          "Runtime prewarm returned an unexpected image digest"
      );
    }
    AiRuntimePool pool = poolRepository.findActiveByIdForUpdate(task.poolId())
        .orElse(null);
    AiRuntimeNode node = nodeRepository.findActiveByNodeIdForUpdate(
        task.nodeId()
    ).orElse(null);
    if (pool == null
        || node == null
        || !Objects.equals(node.getInstanceId(), task.nodeInstanceId())
        || !Objects.equals(pool.getImageDigest(), task.imageDigest())) {
      return;
    }
    node.setCachedImageDigestsJson(imageCacheCodec.add(
        node.getCachedImageDigestsJson(),
        task.imageDigest()
    ));
    nodeRepository.save(node);
    pool.setPrewarmedNodes(pool.getPrewarmedNodes() + 1);
    pool.setLastError(null);
    poolRepository.save(pool);
  }

  /**
   * Persists one sanitized prewarm error and allows a later bounded retry.
   */
  @Transactional(rollbackFor = Exception.class)
  public void recordPrewarmFailure(
      final PrewarmTask task,
      final RuntimeException error
  ) {
    if (task == null) {
      return;
    }
    poolRepository.findActiveByIdForUpdate(task.poolId()).ifPresent(pool -> {
      if (Objects.equals(pool.getImageDigest(), task.imageDigest())
          && pool.getStatus() != RuntimePoolStatus.DISABLED) {
        pool.setStatus(RuntimePoolStatus.ERROR);
        pool.setLastError(safeError(error));
        poolRepository.save(pool);
      }
    });
  }

  /**
   * Converges desired replicas, including idle scale-to-zero and replacement
   * of terminal or lifetime-expired children through the existing workload
   * lease state machine.
   */
  @Transactional(rollbackFor = Exception.class)
  public int reconcile() {
    Instant now = Instant.now();
    List<AiRuntimePool> pools = poolRepository.findReconcileForUpdate(
        RECONCILABLE,
        PageRequest.of(0, batchSize())
    );
    if (pools.isEmpty()) {
      return 0;
    }
    List<AiRuntimeNode> nodes =
        nodeRepository.findSchedulableNodesForUpdate(now);
    int changed = 0;
    for (AiRuntimePool pool : pools) {
      int target = target(pool, now);
      List<AiRuntimeWorkload> all =
          workloadRepository.findActiveByPool(pool.getId());
      List<AiRuntimeWorkload> active = all.stream()
          .filter(workload -> !terminal(workload.getStatus()))
          .toList();
      int cached = (int) nodes.stream()
          .filter(node -> fits(node, pool))
          .filter(node -> cached(node, pool.getImageDigest()))
          .count();
      pool.setPrewarmedNodes(cached);
      int required = Math.min(
          pool.getPrewarmNodes(),
          Math.min(target, (int) nodes.stream()
              .filter(node -> fits(node, pool))
              .count())
      );

      int delta = 0;
      if (active.size() < target && cached >= required) {
        int missing = target - active.size();
        for (int index = 0; index < missing; index++) {
          workloadRepository.save(newReplica(pool, now));
          delta++;
        }
      } else if (active.size() > target) {
        int excess = active.size() - target;
        List<AiRuntimeWorkload> candidates = new ArrayList<>(active);
        candidates.sort(Comparator
            .comparingInt((AiRuntimeWorkload workload) ->
                workload.getStatus() == RuntimeWorkloadStatus.PENDING ? 0 : 1)
            .thenComparing(
                AiRuntimeWorkload::getReplicaSequence,
                Comparator.nullsLast(Comparator.reverseOrder())
            ));
        for (AiRuntimeWorkload workload :
            candidates.subList(0, excess)) {
          requestStop(workload, now);
          workloadRepository.save(workload);
          delta++;
        }
      }

      List<AiRuntimeWorkload> observed =
          workloadRepository.findActiveByPool(pool.getId());
      int current = (int) observed.stream()
          .filter(workload -> !terminal(workload.getStatus()))
          .count();
      int ready = (int) observed.stream()
          .filter(workload ->
              workload.getStatus() == RuntimeWorkloadStatus.RUNNING)
          .count();
      pool.setCurrentReplicas(current);
      pool.setReadyReplicas(ready);
      pool.setLastReconciledAt(now);
      updateStatus(pool, target, required, cached, current, ready);
      poolRepository.save(pool);
      changed += delta;
    }
    return changed;
  }

  private AiRuntimeWorkload newReplica(
      final AiRuntimePool pool,
      final Instant now
  ) {
    long sequence = pool.getNextReplicaSequence();
    if (sequence <= 0 || sequence == Long.MAX_VALUE) {
      throw new IllegalStateException(
          "Runtime pool replica sequence is exhausted"
      );
    }
    pool.setNextReplicaSequence(sequence + 1);
    String poolKey = pool.getId().substring(
        0,
        Math.min(32, pool.getId().length())
    );
    AiRuntimeWorkload workload = new AiRuntimeWorkload();
    workload.setScopeType(pool.getScopeType());
    workload.setTenantId(pool.getTenantId());
    workload.setExecutionId("pool-" + poolKey + "-" + sequence);
    workload.setServerId(pool.getServerId());
    workload.setPoolId(pool.getId());
    workload.setReplicaSequence(sequence);
    workload.setImageReference(pool.getImageReference());
    workload.setImageDigest(pool.getImageDigest());
    workload.setRequestedMemoryBytes(pool.getRequestedMemoryBytes());
    workload.setRequestedNanoCpus(pool.getRequestedNanoCpus());
    workload.setRequestedPidsLimit(pool.getRequestedPidsLimit());
    workload.setTimeoutSeconds(pool.getReplicaLifetimeSeconds());
    workload.setNetworkMode(pool.getNetworkMode());
    workload.setEgressAllowlistJson(pool.getEgressAllowlistJson());
    workload.setSecretReferencesJson(pool.getSecretReferencesJson());
    workload.setStatus(RuntimeWorkloadStatus.PENDING);
    workload.setFencingToken(0);
    workload.setDeadlineAt(now.plusSeconds(pool.getReplicaLifetimeSeconds()));
    return workload;
  }

  private void requestStop(
      final AiRuntimeWorkload workload,
      final Instant now
  ) {
    if (workload.getStatus() == RuntimeWorkloadStatus.PENDING
        && workload.getLeaseId() == null) {
      workload.setStatus(RuntimeWorkloadStatus.CANCELLED);
      workload.setFinishedAt(now);
    } else {
      workload.setStatus(RuntimeWorkloadStatus.STOPPING);
      workload.setLastObservedAt(null);
    }
    workload.setLastError("Runtime pool scale-down requested");
  }

  private void updateStatus(
      final AiRuntimePool pool,
      final int target,
      final int requiredPrewarms,
      final int cached,
      final int current,
      final int ready
  ) {
    if (pool.getStatus() == RuntimePoolStatus.DISABLED) {
      return;
    }
    if (target == 0 && current == 0) {
      pool.setStatus(RuntimePoolStatus.IDLE);
      pool.setLastError(null);
    } else if (cached < requiredPrewarms
        || current != target
        || ready != target) {
      pool.setStatus(RuntimePoolStatus.SCALING);
    } else {
      pool.setStatus(RuntimePoolStatus.READY);
      pool.setLastError(null);
    }
  }

  private int target(final AiRuntimePool pool, final Instant now) {
    if (pool.getStatus() == RuntimePoolStatus.DISABLED) {
      return 0;
    }
    int desired = Math.max(
        pool.getMinReplicas(),
        Math.min(pool.getDesiredReplicas(), pool.getMaxReplicas())
    );
    if (pool.getIdleTimeoutSeconds() > 0
        && !pool.getLastActivityAt()
            .plusSeconds(pool.getIdleTimeoutSeconds())
            .isAfter(now)) {
      return pool.getMinReplicas();
    }
    return desired;
  }

  private boolean prewarmDue(
      final AiRuntimePool pool,
      final Instant now
  ) {
    if (properties.getPoolPrewarmRetryInterval() == null
        || properties.getPoolPrewarmRetryInterval().isZero()
        || properties.getPoolPrewarmRetryInterval().isNegative()) {
      throw new IllegalStateException(
          "Runtime pool prewarm retry interval is invalid"
      );
    }
    return pool.getLastPrewarmAt() == null
        || !pool.getLastPrewarmAt()
            .plus(properties.getPoolPrewarmRetryInterval())
            .isAfter(now);
  }

  private boolean fits(
      final AiRuntimeNode node,
      final AiRuntimePool pool
  ) {
    return pool.getRequestedMemoryBytes() <= node.getMaxWorkloadMemoryBytes()
        && pool.getRequestedNanoCpus() <= node.getMaxWorkloadNanoCpus()
        && pool.getRequestedPidsLimit() <= node.getMaxWorkloadPidsLimit()
        && (!"bridge".equals(pool.getNetworkMode())
            || node.isAllowBridgeNetwork())
        && (!"egress".equals(pool.getNetworkMode())
            || node.isAllowEgressNetwork());
  }

  private boolean cached(
      final AiRuntimeNode node,
      final String digest
  ) {
    return imageCacheCodec.contains(
        node.getCachedImageDigestsJson(),
        digest
    );
  }

  private boolean observedDigest(
      final RuntimeImageObservation observation,
      final String digest
  ) {
    if (digest.equals(observation.imageId())) {
      return true;
    }
    return observation.repoDigests() != null
        && observation.repoDigests().stream().anyMatch(value ->
            value != null && value.endsWith("@" + digest));
  }

  private boolean terminal(final RuntimeWorkloadStatus status) {
    return status == RuntimeWorkloadStatus.SUCCEEDED
        || status == RuntimeWorkloadStatus.FAILED
        || status == RuntimeWorkloadStatus.LOST
        || status == RuntimeWorkloadStatus.CANCELLED;
  }

  private int batchSize() {
    Integer value = properties.getPoolSchedulerBatchSize();
    if (value == null || value <= 0 || value > 256) {
      throw new IllegalStateException(
          "Runtime pool scheduler batch size is invalid"
      );
    }
    return value;
  }

  private String safeError(final RuntimeException error) {
    String message = error == null ? null : error.getMessage();
    String value = message == null || message.isBlank()
        ? (error == null ? "Runtime prewarm failed"
            : error.getClass().getSimpleName())
        : message.trim();
    return value.length() <= 1024 ? value : value.substring(0, 1024);
  }

  /**
   * Immutable slow node operation claimed by one control-plane replica.
   */
  public record PrewarmTask(
      String poolId,
      String nodeId,
      String nodeInstanceId,
      String advertiseUrl,
      String image,
      String imageDigest
  ) {
  }
}
