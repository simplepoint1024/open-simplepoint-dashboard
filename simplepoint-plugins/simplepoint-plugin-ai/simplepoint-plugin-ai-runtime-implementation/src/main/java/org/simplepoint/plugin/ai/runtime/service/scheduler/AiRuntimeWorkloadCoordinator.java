package org.simplepoint.plugin.ai.runtime.service.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeLease;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeLeaseStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeOperationException;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeSecretFile;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadDispatchRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadObservation;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeLeaseRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeWorkloadRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSecretService;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSubjectSecretResolver;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeEgressPolicyCodec;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeImageCacheCodec;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeProfileCodec;
import org.simplepoint.plugin.ai.runtime.service.support.ManagedMcpSessionDirectory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short transactional state transitions for the horizontally safe scheduler.
 */
@Service
public class AiRuntimeWorkloadCoordinator {

  private static final List<RuntimeWorkloadStatus> DISPATCHABLE = List.of(
      RuntimeWorkloadStatus.ASSIGNED,
      RuntimeWorkloadStatus.STARTING
  );

  private static final List<RuntimeWorkloadStatus> OBSERVABLE = List.of(
      RuntimeWorkloadStatus.RUNNING,
      RuntimeWorkloadStatus.STOPPING
  );

  private final AiRuntimeNodeRepository nodeRepository;

  private final AiRuntimeWorkloadRepository workloadRepository;

  private final AiRuntimeLeaseRepository leaseRepository;

  private final AiRuntimeProperties properties;

  private final AiRuntimeSecretService secretService;

  private final List<AiRuntimeSubjectSecretResolver> subjectSecretResolvers;

  private final AiRuntimeEgressPolicyCodec egressPolicyCodec;

  private final AiRuntimeImageCacheCodec imageCacheCodec;

  private final AiRuntimeProfileCodec profileCodec;

  private final ManagedMcpSessionDirectory sessionDirectory;

  /**
   * Creates the transactional scheduler coordinator.
   */
  public AiRuntimeWorkloadCoordinator(
      final AiRuntimeNodeRepository nodeRepository,
      final AiRuntimeWorkloadRepository workloadRepository,
      final AiRuntimeLeaseRepository leaseRepository,
      final AiRuntimeProperties properties,
      final AiRuntimeSecretService secretService,
      final List<AiRuntimeSubjectSecretResolver> subjectSecretResolvers,
      final AiRuntimeEgressPolicyCodec egressPolicyCodec,
      final AiRuntimeImageCacheCodec imageCacheCodec,
      final AiRuntimeProfileCodec profileCodec,
      final ManagedMcpSessionDirectory sessionDirectory
  ) {
    this.nodeRepository = nodeRepository;
    this.workloadRepository = workloadRepository;
    this.leaseRepository = leaseRepository;
    this.properties = properties;
    this.secretService = secretService;
    this.subjectSecretResolvers = subjectSecretResolvers == null
        ? List.of() : List.copyOf(subjectSecretResolvers);
    this.egressPolicyCodec = egressPolicyCodec;
    this.imageCacheCodec = imageCacheCodec;
    this.profileCodec = profileCodec;
    this.sessionDirectory = sessionDirectory;
  }

  /**
   * Binds pending workloads to live nodes while holding node capacity locks.
   */
  @Transactional(rollbackFor = Exception.class)
  public int assignPending() {
    Instant now = Instant.now();
    List<AiRuntimeWorkload> pending =
        workloadRepository.findPendingForUpdate(
            PageRequest.of(0, batchSize())
        );
    if (pending.isEmpty()) {
      return 0;
    }
    List<AiRuntimeNode> nodes =
        nodeRepository.findSchedulableNodesForUpdate(now);
    Map<String, Allocation> allocations = currentAllocations(nodes);
    int assigned = 0;
    for (AiRuntimeWorkload workload : pending) {
      if (!workload.getDeadlineAt().isAfter(now)) {
        workload.setStatus(RuntimeWorkloadStatus.CANCELLED);
        workload.setFinishedAt(now);
        workload.setLastError(
            "Runtime workload expired before node assignment"
        );
        workloadRepository.save(workload);
        continue;
      }
      Optional<AiRuntimeNode> selected = selectNode(
          nodes,
          allocations,
          workload
      );
      if (selected.isEmpty()) {
        String reason = schedulingFailure(nodes, allocations, workload);
        if (!Objects.equals(reason, workload.getLastError())) {
          workload.setLastError(reason);
          workloadRepository.save(workload);
        }
        continue;
      }
      AiRuntimeNode node = selected.orElseThrow();
      long fencingToken = nextFence(workload.getFencingToken());
      AiRuntimeLease lease = new AiRuntimeLease();
      lease.setWorkloadId(workload.getId());
      lease.setNodeId(node.getNodeId());
      lease.setNodeInstanceId(node.getInstanceId());
      lease.setFencingToken(fencingToken);
      lease.setStatus(RuntimeLeaseStatus.ACTIVE);
      lease.setAcquiredAt(now);
      lease.setRenewedAt(now);
      lease.setExpiresAt(now.plus(leaseDuration()));
      lease = leaseRepository.save(lease);

      workload.setAssignedNodeId(node.getNodeId());
      workload.setLeaseId(lease.getId());
      workload.setFencingToken(fencingToken);
      workload.setRuntimeWorkloadId(workload.getId());
      workload.setStatus(RuntimeWorkloadStatus.ASSIGNED);
      workload.setLastObservedAt(null);
      workload.setLastError(null);
      workloadRepository.save(workload);
      allocations.compute(node.getNodeId(), (ignored, current) ->
          current == null ? Allocation.of(workload) : current.add(workload));
      assigned++;
    }
    return assigned;
  }

  /**
   * Claims first or retry dispatches and renews their ownership leases.
   */
  @Transactional(rollbackFor = Exception.class)
  public List<DispatchTask> claimDispatches() {
    Instant now = Instant.now();
    Instant retryBefore = now.minus(dispatchRetryInterval());
    List<AiRuntimeWorkload> candidates =
        workloadRepository.findDispatchableForUpdate(
            DISPATCHABLE,
            retryBefore,
            PageRequest.of(0, batchSize())
        );
    List<DispatchTask> tasks = new ArrayList<>();
    for (AiRuntimeWorkload workload : candidates) {
      Assignment assignment = currentAssignment(workload, now);
      if (assignment == null) {
        continue;
      }
      if (!workload.getDeadlineAt().isAfter(now)) {
        workload.setStatus(RuntimeWorkloadStatus.STOPPING);
        workload.setLastObservedAt(null);
        workload.setLastError("Runtime workload execution deadline expired");
        workloadRepository.save(workload);
        continue;
      }
      renew(assignment.lease(), now);
      workload.setStatus(RuntimeWorkloadStatus.STARTING);
      workload.setLastObservedAt(now);
      workloadRepository.save(workload);
      tasks.add(new DispatchTask(
          workload.getId(),
          assignment.lease().getId(),
          assignment.lease().getFencingToken(),
          assignment.node().getAdvertiseUrl(),
          dispatchRequest(workload, assignment.lease(), now)
      ));
    }
    return List.copyOf(tasks);
  }

  /**
   * Claims running state observations and requested or deadline stops.
   */
  @Transactional(rollbackFor = Exception.class)
  public List<ObservationTask> claimObservations() {
    Instant now = Instant.now();
    List<AiRuntimeWorkload> candidates =
        workloadRepository.findObservableForUpdate(
            OBSERVABLE,
            now,
            now.minus(observationInterval()),
            PageRequest.of(0, batchSize())
        );
    List<ObservationTask> tasks = new ArrayList<>();
    for (AiRuntimeWorkload workload : candidates) {
      Assignment assignment = currentAssignment(workload, now);
      if (assignment == null) {
        continue;
      }
      boolean stop = workload.getStatus() == RuntimeWorkloadStatus.STOPPING
          || !workload.getDeadlineAt().isAfter(now);
      if (stop) {
        workload.setStatus(RuntimeWorkloadStatus.STOPPING);
      }
      renew(assignment.lease(), now);
      workload.setLastObservedAt(now);
      workloadRepository.save(workload);
      tasks.add(new ObservationTask(
          workload.getId(),
          assignment.lease().getId(),
          assignment.lease().getFencingToken(),
          assignment.node().getAdvertiseUrl(),
          stop
      ));
    }
    return List.copyOf(tasks);
  }

  /**
   * Applies a successful start response only when its lease is still current.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean confirmStarted(
      final DispatchTask task,
      final RuntimeWorkloadObservation observation
  ) {
    return confirm(
        task.workloadId(),
        task.leaseId(),
        task.fencingToken(),
        false,
        observation
    );
  }

  /**
   * Applies a successful observation or stop response under the same fence.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean confirmObserved(
      final ObservationTask task,
      final RuntimeWorkloadObservation observation
  ) {
    return confirm(
        task.workloadId(),
        task.leaseId(),
        task.fencingToken(),
        task.stop(),
        observation
    );
  }

  /**
   * Records a sanitized node failure without allowing a stale task to mutate
   * a newer assignment.
   */
  @Transactional(rollbackFor = Exception.class)
  public void recordFailure(
      final String workloadId,
      final String leaseId,
      final long fencingToken,
      final boolean stopping,
      final RuntimeException failure
  ) {
    AiRuntimeWorkload workload =
        workloadRepository.findActiveByIdForUpdate(workloadId).orElse(null);
    if (workload == null || !matches(workload, leaseId, fencingToken)) {
      return;
    }
    AiRuntimeLease lease =
        leaseRepository.findActiveByWorkloadForUpdate(workloadId).orElse(null);
    if (lease == null || !matches(lease, leaseId, fencingToken)) {
      return;
    }
    int statusCode = failure instanceof RuntimeNodeOperationException operation
        ? operation.getStatusCode() : 0;
    workload.setLastObservedAt(Instant.now());
    workload.setLastError(safeMessage(failure));
    if (statusCode == 400 || statusCode == 401 || statusCode == 403) {
      workload.setStatus(RuntimeWorkloadStatus.FAILED);
      workload.setFinishedAt(Instant.now());
      reclaimSessions(workload, lease);
      release(lease, RuntimeLeaseStatus.RELEASED);
    } else if (statusCode == 404 || statusCode == 409) {
      workload.setStatus(stopping
          ? RuntimeWorkloadStatus.CANCELLED : RuntimeWorkloadStatus.LOST);
      workload.setFinishedAt(Instant.now());
      reclaimSessions(workload, lease);
      release(lease, RuntimeLeaseStatus.FENCED);
    }
    workloadRepository.save(workload);
  }

  /**
   * Expires abandoned leases and makes unfinished work eligible for a newer
   * fencing token.
   */
  @Transactional(rollbackFor = Exception.class)
  public int expireLeases() {
    Instant now = Instant.now();
    List<AiRuntimeLease> expired =
        leaseRepository.findExpiredActiveLeases(
            RuntimeLeaseStatus.ACTIVE,
            now
        );
    int count = 0;
    for (AiRuntimeLease lease : expired) {
      lease.setStatus(RuntimeLeaseStatus.EXPIRED);
      lease.setReleasedAt(now);
      leaseRepository.save(lease);
      AiRuntimeWorkload workload =
          workloadRepository.findActiveByIdForUpdate(
              lease.getWorkloadId()
          ).orElse(null);
      if (workload == null
          || !matches(
              workload,
              lease.getId(),
              lease.getFencingToken()
          )) {
        continue;
      }
      reclaimSessions(workload, lease);
      if (isTerminal(workload.getStatus())) {
        continue;
      }
      if (!workload.getDeadlineAt().isAfter(now)) {
        workload.setStatus(
            workload.getStatus() == RuntimeWorkloadStatus.STOPPING
                ? RuntimeWorkloadStatus.CANCELLED
                : RuntimeWorkloadStatus.LOST
        );
        workload.setFinishedAt(now);
      } else {
        resetForReschedule(
            workload,
            "Runtime workload lease expired; awaiting reassignment"
        );
      }
      workloadRepository.save(workload);
      count++;
    }
    return count;
  }

  private boolean confirm(
      final String workloadId,
      final String leaseId,
      final long fencingToken,
      final boolean stopping,
      final RuntimeWorkloadObservation observation
  ) {
    AiRuntimeWorkload workload =
        workloadRepository.findActiveByIdForUpdate(workloadId).orElse(null);
    if (workload == null || !matches(workload, leaseId, fencingToken)) {
      return false;
    }
    AiRuntimeLease lease =
        leaseRepository.findActiveByWorkloadForUpdate(workloadId).orElse(null);
    if (lease == null
        || !matches(lease, leaseId, fencingToken)
        || !validObservation(workload, lease, observation)) {
      return false;
    }
    Instant now = Instant.now();
    workload.setContainerId(observation.containerId());
    workload.setStartedAt(observation.startedAt());
    workload.setFinishedAt(observation.finishedAt());
    workload.setLastObservedAt(now);
    applyEngineState(workload, observation, stopping, now);
    if (isTerminal(workload.getStatus())) {
      reclaimSessions(workload, lease);
      release(lease, RuntimeLeaseStatus.RELEASED);
    } else {
      renew(lease, now);
    }
    workloadRepository.save(workload);
    return true;
  }

  private Assignment currentAssignment(
      final AiRuntimeWorkload workload,
      final Instant now
  ) {
    AiRuntimeLease lease =
        leaseRepository.findActiveByWorkloadForUpdate(
            workload.getId()
        ).orElse(null);
    if (lease == null
        || !matches(
            workload,
            lease.getId(),
            lease.getFencingToken()
        )
        || !lease.getExpiresAt().isAfter(now)) {
      if (lease != null) {
        reclaimSessions(workload, lease);
        release(
            lease,
            lease.getExpiresAt().isAfter(now)
                ? RuntimeLeaseStatus.FENCED
                : RuntimeLeaseStatus.EXPIRED
        );
      }
      resetForReschedule(
          workload,
          "Runtime workload assignment has no active lease"
      );
      workloadRepository.save(workload);
      return null;
    }
    AiRuntimeNode node = nodeRepository.findActiveByNodeId(
        lease.getNodeId()
    ).orElse(null);
    if (node == null
        || !Objects.equals(node.getInstanceId(), lease.getNodeInstanceId())
        || !node.getHeartbeatExpiresAt().isAfter(now)
        || (node.getStatus() != RuntimeNodeStatus.READY
          && node.getStatus() != RuntimeNodeStatus.DRAINING)) {
      reclaimSessions(workload, lease);
      release(lease, RuntimeLeaseStatus.FENCED);
      if (workload.getStatus() == RuntimeWorkloadStatus.STOPPING) {
        workload.setStatus(RuntimeWorkloadStatus.CANCELLED);
        workload.setFinishedAt(now);
      } else {
        resetForReschedule(
            workload,
            "Runtime node generation is unavailable; awaiting reassignment"
        );
      }
      workloadRepository.save(workload);
      return null;
    }
    return new Assignment(node, lease);
  }

  private Map<String, Allocation> currentAllocations(
      final List<AiRuntimeNode> nodes
  ) {
    Map<String, Allocation> result = new HashMap<>();
    for (AiRuntimeNode node : nodes) {
      List<AiRuntimeLease> leases = leaseRepository.findByNodeAndStatus(
          node.getNodeId(),
          RuntimeLeaseStatus.ACTIVE
      );
      List<String> workloadIds = leases.stream()
          .map(AiRuntimeLease::getWorkloadId)
          .toList();
      List<AiRuntimeWorkload> workloads = workloadIds.isEmpty()
          ? List.of() : workloadRepository.findAllByIds(workloadIds);
      result.put(
          node.getNodeId(),
          workloads.stream()
              .filter(workload -> !isTerminal(workload.getStatus()))
              .reduce(
                  Allocation.empty(),
                  Allocation::add,
                  Allocation::add
              )
      );
    }
    return result;
  }

  private Optional<AiRuntimeNode> selectNode(
      final List<AiRuntimeNode> nodes,
      final Map<String, Allocation> allocations,
      final AiRuntimeWorkload workload
  ) {
    return nodes.stream()
        .filter(node -> fits(
            node,
            allocations.getOrDefault(
                node.getNodeId(),
                Allocation.empty()
            ),
            workload
        ))
        .min(Comparator
            .comparingLong((AiRuntimeNode node) ->
                allocations.getOrDefault(
                    node.getNodeId(),
                    Allocation.empty()
                ).poolCount(workload.getPoolId()))
            .thenComparing((AiRuntimeNode node) ->
                !imageCacheCodec.contains(
                    node.getCachedImageDigestsJson(),
                    workload.getImageDigest()
                ))
            .thenComparingDouble(node ->
                allocations.getOrDefault(
                    node.getNodeId(),
                    Allocation.empty()
                ).utilization(node))
            .thenComparing(AiRuntimeNode::getNodeId));
  }

  private boolean fits(
      final AiRuntimeNode node,
      final Allocation allocation,
      final AiRuntimeWorkload workload
  ) {
    if ("bridge".equals(workload.getNetworkMode())
        && !node.isAllowBridgeNetwork()) {
      return false;
    }
    if ("egress".equals(workload.getNetworkMode())
        && !node.isAllowEgressNetwork()) {
      return false;
    }
    if (workload.getRequestedMemoryBytes()
            > node.getMaxWorkloadMemoryBytes()
        || workload.getRequestedNanoCpus()
            > node.getMaxWorkloadNanoCpus()
        || workload.getRequestedPidsLimit()
            > node.getMaxWorkloadPidsLimit()
        || allocation.count() >= node.getMaxWorkloads()) {
      return false;
    }
    long totalNanoCpus = (long) node.getCpuCores() * 1_000_000_000L;
    return allocation.memoryBytes()
            <= node.getMemoryBytes() - workload.getRequestedMemoryBytes()
        && allocation.nanoCpus()
            <= totalNanoCpus - workload.getRequestedNanoCpus();
  }

  private String schedulingFailure(
      final List<AiRuntimeNode> nodes,
      final Map<String, Allocation> allocations,
      final AiRuntimeWorkload workload
  ) {
    if (nodes.isEmpty()) {
      return "No live READY OCI Runtime node is available";
    }
    if ("bridge".equals(workload.getNetworkMode())
        && nodes.stream().noneMatch(AiRuntimeNode::isAllowBridgeNetwork)) {
      return "No compatible OCI Runtime node allows bridge networking";
    }
    if ("egress".equals(workload.getNetworkMode())
        && nodes.stream().noneMatch(AiRuntimeNode::isAllowEgressNetwork)) {
      return "No compatible OCI Runtime node allows egress networking";
    }
    if (nodes.stream().noneMatch(node ->
        workload.getRequestedMemoryBytes()
            <= node.getMaxWorkloadMemoryBytes())) {
      return "No compatible OCI Runtime node accepts the requested memory limit";
    }
    if (nodes.stream().noneMatch(node ->
        workload.getRequestedNanoCpus()
            <= node.getMaxWorkloadNanoCpus())) {
      return "No compatible OCI Runtime node accepts the requested CPU limit";
    }
    if (nodes.stream().noneMatch(node ->
        workload.getRequestedPidsLimit()
            <= node.getMaxWorkloadPidsLimit())) {
      return "No compatible OCI Runtime node accepts the requested PID limit";
    }
    if (nodes.stream().noneMatch(node ->
        allocations.getOrDefault(node.getNodeId(), Allocation.empty()).count()
            < node.getMaxWorkloads())) {
      return "All compatible OCI Runtime nodes reached their workload limit";
    }
    return "No OCI Runtime node currently has enough free capacity";
  }

  private RuntimeWorkloadDispatchRequest dispatchRequest(
      final AiRuntimeWorkload workload,
      final AiRuntimeLease lease,
      final Instant now
  ) {
    RuntimeMcpProfileSpec profile = workload.getRuntimeSpecJson() == null
        ? null : profileCodec.decode(workload.getRuntimeSpecJson());
    long remainingSeconds = Math.max(
        1,
        Duration.between(now, workload.getDeadlineAt()).toSeconds()
    );
    return new RuntimeWorkloadDispatchRequest(
        workload.getId(),
        lease.getId(),
        lease.getFencingToken(),
        workload.getExecutionId(),
        workload.getTenantId() == null ? "" : workload.getTenantId(),
        workload.getImageReference() + "@" + workload.getImageDigest(),
        workload.getRequestedMemoryBytes(),
        workload.getRequestedNanoCpus(),
        workload.getRequestedPidsLimit(),
        remainingSeconds,
        workload.getNetworkMode(),
        egressPolicyCodec.decode(
            workload.getNetworkMode(),
            workload.getEgressAllowlistJson()
        ),
        dispatchSecrets(workload, profile),
        profile == null ? "stdio" : transport(profile.transport().type()),
        profile == null ? List.of() : profile.process().entrypoint(),
        profile == null ? List.of() : profile.process().command(),
        profile == null ? List.of() : profile.process().arguments(),
        profile == null ? null : profile.process().workingDirectory(),
        profile == null ? Map.of() : profileCodec.environment(profile),
        profile == null ? null : profile.session().mode().name(),
        profile == null ? 1 : profile.session().maxSessions(),
        profile == null ? List.of() : profileCodec.storage(
            profile, workload.getScopeType(), workload.getTenantId()
        ),
        profile == null ? null : profile.transport().containerPort(),
        profile == null ? null : profile.transport().path(),
        profile == null ? null : profile.sandbox().profile().name(),
        profile == null ? null : profile.process().userMode().name(),
        profile == null
            ? List.of() : profile.process().initializationCommand()
    );
  }

  private List<RuntimeSecretFile> dispatchSecrets(
      final AiRuntimeWorkload workload,
      final RuntimeMcpProfileSpec profile
  ) {
    if (profile == null) {
      return secretService.resolve(
          workload.getSecretReferencesJson(),
          workload.getScopeType(),
          workload.getTenantId()
      );
    }
    List<RuntimeSecretFile> result = new ArrayList<>(
        secretService.resolveBindings(
            profile.secrets(),
            workload.getScopeType(),
            workload.getTenantId()
        )
    );
    for (AiRuntimeSubjectSecretResolver resolver : subjectSecretResolvers) {
      result.addAll(resolver.resolve(
          workload.getServerId(),
          workload.getScopeType(),
          workload.getTenantId(),
          profile.secrets()
      ));
    }
    return List.copyOf(result);
  }

  private static String transport(
      final RuntimeMcpProfileSpec.TransportType type
  ) {
    return switch (type) {
      case STDIO -> "stdio";
      case STREAMABLE_HTTP -> "streamable-http";
      case SSE_LEGACY -> "sse";
    };
  }

  private boolean validObservation(
      final AiRuntimeWorkload workload,
      final AiRuntimeLease lease,
      final RuntimeWorkloadObservation observation
  ) {
    return observation != null
        && Objects.equals(observation.workloadId(), workload.getId())
        && Objects.equals(observation.leaseId(), lease.getId())
        && observation.fencingToken() == lease.getFencingToken()
        && Objects.equals(
            observation.runtimeNode(),
            workload.getAssignedNodeId()
        );
  }

  private void applyEngineState(
      final AiRuntimeWorkload workload,
      final RuntimeWorkloadObservation observation,
      final boolean stopping,
      final Instant now
  ) {
    String state = observation.state() == null
        ? "" : observation.state().trim().toLowerCase(Locale.ROOT);
    switch (state) {
      case "running", "restarting", "paused" -> {
        workload.setStatus(RuntimeWorkloadStatus.RUNNING);
        workload.setLastError(null);
      }
      case "created" -> workload.setStatus(RuntimeWorkloadStatus.STARTING);
      case "exited" -> {
        workload.setStatus(stopping
            ? RuntimeWorkloadStatus.CANCELLED
            : observation.exitCode() == 0
                ? RuntimeWorkloadStatus.SUCCEEDED
                : RuntimeWorkloadStatus.FAILED);
        workload.setFinishedAt(
            observation.finishedAt() == null ? now : observation.finishedAt()
        );
        workload.setLastError(
            stopping || observation.exitCode() == 0
                ? null
                : "OCI workload exited with code " + observation.exitCode()
        );
      }
      case "dead", "removing" -> {
        workload.setStatus(stopping
            ? RuntimeWorkloadStatus.CANCELLED
            : RuntimeWorkloadStatus.FAILED);
        workload.setFinishedAt(now);
      }
      default -> {
        workload.setStatus(RuntimeWorkloadStatus.STARTING);
        workload.setLastError("OCI workload state is not yet observable");
      }
    }
  }

  private void resetForReschedule(
      final AiRuntimeWorkload workload,
      final String reason
  ) {
    workload.setStatus(RuntimeWorkloadStatus.PENDING);
    workload.setAssignedNodeId(null);
    workload.setLeaseId(null);
    workload.setRuntimeWorkloadId(null);
    workload.setContainerId(null);
    workload.setStartedAt(null);
    workload.setLastObservedAt(null);
    workload.setLastError(reason);
  }

  private void renew(final AiRuntimeLease lease, final Instant now) {
    lease.setRenewedAt(now);
    lease.setExpiresAt(now.plus(leaseDuration()));
    leaseRepository.save(lease);
  }

  private void reclaimSessions(
      final AiRuntimeWorkload workload,
      final AiRuntimeLease lease
  ) {
    String runtimeWorkloadId = workload.getRuntimeWorkloadId();
    if (runtimeWorkloadId == null || runtimeWorkloadId.isBlank()) {
      runtimeWorkloadId = workload.getId();
    }
    sessionDirectory.invalidateWorkload(
        new ManagedMcpSessionDirectory.Assignment(
            runtimeWorkloadId,
            lease.getId(),
            lease.getFencingToken()
        )
    );
  }

  private void release(
      final AiRuntimeLease lease,
      final RuntimeLeaseStatus status
  ) {
    Instant now = Instant.now();
    lease.setStatus(status);
    lease.setReleasedAt(now);
    lease.setExpiresAt(now);
    leaseRepository.save(lease);
  }

  private boolean matches(
      final AiRuntimeWorkload workload,
      final String leaseId,
      final long fencingToken
  ) {
    return Objects.equals(workload.getLeaseId(), leaseId)
        && workload.getFencingToken() == fencingToken;
  }

  private boolean matches(
      final AiRuntimeLease lease,
      final String leaseId,
      final long fencingToken
  ) {
    return Objects.equals(lease.getId(), leaseId)
        && lease.getFencingToken() == fencingToken
        && lease.getStatus() == RuntimeLeaseStatus.ACTIVE;
  }

  private boolean isTerminal(final RuntimeWorkloadStatus status) {
    return status == RuntimeWorkloadStatus.SUCCEEDED
        || status == RuntimeWorkloadStatus.FAILED
        || status == RuntimeWorkloadStatus.LOST
        || status == RuntimeWorkloadStatus.CANCELLED;
  }

  private int batchSize() {
    Integer value = properties.getWorkloadSchedulerBatchSize();
    if (value == null || value <= 0 || value > 256) {
      throw new IllegalStateException(
          "Runtime scheduler batch size is invalid"
      );
    }
    return value;
  }

  private Duration leaseDuration() {
    return requireDuration(
        properties.getWorkloadLeaseDuration(),
        "Runtime workload lease duration",
        Duration.ofSeconds(5),
        Duration.ofMinutes(10)
    );
  }

  private Duration dispatchRetryInterval() {
    return requireDuration(
        properties.getWorkloadDispatchRetryInterval(),
        "Runtime workload dispatch retry interval",
        Duration.ofSeconds(1),
        Duration.ofMinutes(5)
    );
  }

  private Duration observationInterval() {
    return requireDuration(
        properties.getWorkloadObservationInterval(),
        "Runtime workload observation interval",
        Duration.ofSeconds(1),
        Duration.ofMinutes(5)
    );
  }

  private Duration requireDuration(
      final Duration value,
      final String name,
      final Duration minimum,
      final Duration maximum
  ) {
    if (value == null
        || value.compareTo(minimum) < 0
        || value.compareTo(maximum) > 0) {
      throw new IllegalStateException(name + " is invalid");
    }
    return value;
  }

  private long nextFence(final long current) {
    try {
      return Math.addExact(current, 1L);
    } catch (ArithmeticException ex) {
      throw new IllegalStateException(
          "Runtime workload fencing token is exhausted",
          ex
      );
    }
  }

  private String safeMessage(final RuntimeException failure) {
    String message = failure == null ? null : failure.getMessage();
    if (message == null || message.isBlank()) {
      message = failure == null
          ? "Runtime node operation failed"
          : failure.getClass().getSimpleName();
    }
    return message.length() <= 1024
        ? message : message.substring(0, 1024);
  }

  /**
   * Immutable dispatch claim executed outside the database transaction.
   */
  public record DispatchTask(
      String workloadId,
      String leaseId,
      long fencingToken,
      String advertiseUrl,
      RuntimeWorkloadDispatchRequest request
  ) {
  }

  /**
   * Immutable observation claim executed outside the database transaction.
   */
  public record ObservationTask(
      String workloadId,
      String leaseId,
      long fencingToken,
      String advertiseUrl,
      boolean stop
  ) {
  }

  private record Assignment(AiRuntimeNode node, AiRuntimeLease lease) {
  }

  private record Allocation(
      long count,
      long memoryBytes,
      long nanoCpus,
      Map<String, Long> poolCounts
  ) {

    private static Allocation empty() {
      return new Allocation(0, 0, 0, Map.of());
    }

    private static Allocation of(final AiRuntimeWorkload workload) {
      return empty().add(workload);
    }

    private Allocation add(final AiRuntimeWorkload workload) {
      Map<String, Long> updatedPoolCounts = new HashMap<>(poolCounts);
      if (workload.getPoolId() != null) {
        updatedPoolCounts.merge(workload.getPoolId(), 1L, Long::sum);
      }
      return new Allocation(
          count + 1,
          Math.addExact(memoryBytes, workload.getRequestedMemoryBytes()),
          Math.addExact(nanoCpus, workload.getRequestedNanoCpus()),
          Map.copyOf(updatedPoolCounts)
      );
    }

    private Allocation add(final Allocation other) {
      Map<String, Long> updatedPoolCounts = new HashMap<>(poolCounts);
      other.poolCounts.forEach((poolId, replicas) ->
          updatedPoolCounts.merge(poolId, replicas, Long::sum));
      return new Allocation(
          Math.addExact(count, other.count),
          Math.addExact(memoryBytes, other.memoryBytes),
          Math.addExact(nanoCpus, other.nanoCpus),
          Map.copyOf(updatedPoolCounts)
      );
    }

    private long poolCount(final String poolId) {
      return poolId == null ? 0L : poolCounts.getOrDefault(poolId, 0L);
    }

    private double utilization(final AiRuntimeNode node) {
      double slots = (double) count / Math.max(1, node.getMaxWorkloads());
      double memory = (double) memoryBytes
          / Math.max(1L, node.getMemoryBytes());
      double cpu = (double) nanoCpus
          / Math.max(1L, (long) node.getCpuCores() * 1_000_000_000L);
      return Math.max(slots, Math.max(memory, cpu));
    }
  }
}
