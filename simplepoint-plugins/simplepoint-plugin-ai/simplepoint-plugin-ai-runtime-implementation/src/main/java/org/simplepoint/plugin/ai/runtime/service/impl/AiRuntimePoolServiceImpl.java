package org.simplepoint.plugin.ai.runtime.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageResolution;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolScaleRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolUpsertRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimePoolRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeWorkloadRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeImageResolver;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimePoolService;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSecretService;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeEgressPolicyCodec;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-aware desired-state management for reusable OCI MCP runtime pools.
 */
@Service
public class AiRuntimePoolServiceImpl implements AiRuntimePoolService {

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private static final Pattern DIGEST =
      Pattern.compile("^sha256:[a-f0-9]{64}$");

  private static final long MINIMUM_MEMORY_BYTES = 32L * 1024 * 1024;

  private static final long MAXIMUM_MEMORY_BYTES = 16L * 1024 * 1024 * 1024;

  private static final int MAXIMUM_REPLICAS = 256;

  private final AiRuntimePoolRepository repository;

  private final AiRuntimeWorkloadRepository workloadRepository;

  private final AiRuntimeProperties properties;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiRuntimeSecretService secretService;

  private final AiRuntimeEgressPolicyCodec egressPolicyCodec;

  private final AiRuntimeMcpAdmissionService admissionService;

  private final AiRuntimeImageResolver imageResolver;

  /**
   * Creates the current-scope runtime pool service.
   */
  public AiRuntimePoolServiceImpl(
      final AiRuntimePoolRepository repository,
      final AiRuntimeWorkloadRepository workloadRepository,
      final AiRuntimeProperties properties,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiRuntimeSecretService secretService,
      final AiRuntimeEgressPolicyCodec egressPolicyCodec,
      final AiRuntimeMcpAdmissionService admissionService,
      final AiRuntimeImageResolver imageResolver
  ) {
    this.repository = repository;
    this.workloadRepository = workloadRepository;
    this.properties = properties;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.secretService = secretService;
    this.egressPolicyCodec = egressPolicyCodec;
    this.admissionService = admissionService;
    this.imageResolver = imageResolver;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimePool upsert(final RuntimePoolUpsertRequest request) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    NormalizedPool normalized = normalize(request, scope);
    Instant now = Instant.now();
    AiRuntimePool pool = repository.findActiveByCodeForUpdate(
        scope.scopeType(),
        scope.tenantId(),
        normalized.code()
    ).orElseGet(() -> newPool(scope, normalized.code(), now));
    boolean persisted = pool.getId() != null;
    if (persisted && hasRuntimeProfile(pool)) {
      throw new IllegalArgumentException(
          "Runtime Profile-managed pool cannot be edited as a legacy OCI pool"
      );
    }
    boolean runtimeDefinitionChanged = !persisted
        || runtimeSpecChanged(pool, normalized);
    boolean wasDormant = !persisted
        || pool.getDesiredReplicas() == 0
        || pool.getStatus() == RuntimePoolStatus.DISABLED
        || pool.getStatus() == RuntimePoolStatus.ERROR;
    final boolean replaceReplicas = persisted && runtimeDefinitionChanged;
    repository.findActiveByServerAndScope(
        normalized.serverId(),
        scope.scopeType(),
        scope.tenantId()
    ).filter(existing -> !existing.getId().equals(pool.getId()))
        .ifPresent(existing -> {
          throw new IllegalArgumentException(
              "MCP server already owns Runtime pool " + existing.getCode()
          );
        });
    apply(pool, normalized);
    if (pool.getDesiredReplicas() > 0
        && (runtimeDefinitionChanged || wasDormant)) {
      admissionService.admitLegacy(pool);
    }
    if (replaceReplicas) {
      requestReplicaReplacement(pool, now, "Runtime pool definition changed");
    }
    pool.setStatus(RuntimePoolStatus.SCALING);
    pool.setLastActivityAt(now);
    pool.setLastError(null);
    return decorate(repository.save(pool));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiRuntimePool> findAll(final Pageable pageable) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    Page<AiRuntimePool> result = repository.findAllActiveByScope(
        scope.scopeType(),
        scope.tenantId(),
        pageable
    );
    result.getContent().forEach(this::decorate);
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiRuntimePool> find(final String poolId) {
    Optional<AiRuntimePool> result = repository.findById(
        requireIdentifier(poolId, "Runtime pool ID")
    ).filter(pool -> pool.getDeletedAt() == null);
    result.ifPresent(pool -> scopeAccessPolicy.assertCanReadManagedResource(
        pool.getScopeType(),
        pool.getTenantId()
    ));
    return result.map(this::decorate);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiRuntimePool> findByServer(final String serverId) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    return repository.findActiveByServerAndScope(
        requireIdentifier(serverId, "MCP server ID"),
        scope.scopeType(),
        scope.tenantId()
    ).map(this::decorate);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimePool scale(
      final String poolId,
      final RuntimePoolScaleRequest request
  ) {
    AiRuntimePool pool = requireManaged(poolId);
    if (request == null
        || request.desiredReplicas() < 0
        || request.desiredReplicas() > pool.getMaxReplicas()) {
      throw new IllegalArgumentException(
          "Runtime pool desired replica count is invalid"
      );
    }
    if (request.desiredReplicas() > 0
        && (pool.getDesiredReplicas() == 0
          || pool.getStatus() == RuntimePoolStatus.DISABLED
          || pool.getStatus() == RuntimePoolStatus.ERROR)) {
      admitLegacy(pool);
    }
    pool.setDesiredReplicas(request.desiredReplicas());
    pool.setStatus(RuntimePoolStatus.SCALING);
    pool.setLastActivityAt(Instant.now());
    pool.setLastError(null);
    return decorate(repository.save(pool));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimePool activate(final String poolId) {
    AiRuntimePool pool = requireManaged(poolId);
    admitLegacy(pool);
    pool.setDesiredReplicas(Math.max(
        pool.getDesiredReplicas(),
        pool.getActivationReplicas()
    ));
    pool.setStatus(RuntimePoolStatus.SCALING);
    pool.setLastActivityAt(Instant.now());
    pool.setLastError(null);
    return decorate(repository.save(pool));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimePool disable(final String poolId) {
    AiRuntimePool pool = requireManaged(poolId);
    pool.setDesiredReplicas(0);
    pool.setStatus(RuntimePoolStatus.DISABLED);
    pool.setLastError(null);
    return decorate(repository.save(pool));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimePool redeploy(final String poolId) {
    AiRuntimePool pool = requireManaged(poolId);
    if (pool.getStatus() == RuntimePoolStatus.DISABLED) {
      throw new IllegalArgumentException(
          "Disabled Runtime pool must be activated before redeploy"
      );
    }
    admitLegacy(pool);
    Instant now = Instant.now();
    requestReplicaReplacement(pool, now, "Runtime pool redeploy requested");
    pool.setStatus(RuntimePoolStatus.SCALING);
    pool.setLastActivityAt(now);
    pool.setLastError(null);
    return decorate(repository.save(pool));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void remove(final String poolId) {
    AiRuntimePool pool = requireManaged(poolId);
    List<AiRuntimeWorkload> workloads =
        workloadRepository.findActiveByPool(pool.getId());
    boolean active = workloads.stream()
        .anyMatch(workload -> !isTerminal(workload.getStatus()));
    if (pool.getStatus() != RuntimePoolStatus.DISABLED
        || pool.getCurrentReplicas() != 0
        || pool.getReadyReplicas() != 0
        || active) {
      throw new IllegalArgumentException(
          "Runtime pool must be disabled and fully reclaimed before deletion"
      );
    }
    Instant now = Instant.now();
    workloads.forEach(workload -> {
      workload.setDeletedAt(now);
      workloadRepository.save(workload);
    });
    pool.setDeletedAt(now);
    repository.save(pool);
  }

  private AiRuntimePool requireManaged(final String poolId) {
    AiRuntimePool pool = repository.findActiveByIdForUpdate(
        requireIdentifier(poolId, "Runtime pool ID")
    ).orElseThrow(() -> new IllegalArgumentException(
        "Runtime pool does not exist"
    ));
    scopeAccessPolicy.assertCanManageOwnedResource(
        pool.getScopeType(),
        pool.getTenantId()
    );
    return pool;
  }

  private void admitLegacy(final AiRuntimePool pool) {
    if (!hasRuntimeProfile(pool)) {
      admissionService.admitLegacy(pool);
    }
  }

  private boolean hasRuntimeProfile(final AiRuntimePool pool) {
    return pool.getRuntimeProfileId() != null
        && !pool.getRuntimeProfileId().isBlank();
  }

  private NormalizedPool normalize(
      final RuntimePoolUpsertRequest request,
      final ScopeAssignment scope
  ) {
    if (request == null) {
      throw new IllegalArgumentException("Runtime pool definition is required");
    }
    final String code =
        requireIdentifier(request.code(), "Runtime pool code");
    final String name =
        requireText(request.name(), "Runtime pool name", 128);
    final String serverId =
        requireIdentifier(request.serverId(), "MCP server ID");
    final String imageReference =
        normalizeImageReference(request.imageReference());
    final String imageDigest = resolveImageDigest(
        imageReference,
        request.imageDigest()
    );
    long memoryBytes = defaulted(
        request.memoryBytes(),
        properties.getDefaultWorkloadMemoryBytes()
    );
    long nanoCpus = defaulted(
        request.nanoCpus(),
        properties.getDefaultWorkloadNanoCpus()
    );
    long pidsLimit = defaulted(
        request.pidsLimit(),
        properties.getDefaultWorkloadPidsLimit()
    );
    if (memoryBytes < MINIMUM_MEMORY_BYTES
        || memoryBytes > MAXIMUM_MEMORY_BYTES
        || nanoCpus <= 0
        || nanoCpus > 16_000_000_000L
        || pidsLimit <= 0
        || pidsLimit > 4096) {
      throw new IllegalArgumentException(
          "Runtime pool resource limits are invalid"
      );
    }
    if (request.minReplicas() < 0
        || request.maxReplicas() <= 0
        || request.maxReplicas() > MAXIMUM_REPLICAS
        || request.minReplicas() > request.maxReplicas()
        || request.desiredReplicas() < request.minReplicas()
        || request.desiredReplicas() > request.maxReplicas()
        || request.activationReplicas() <= 0
        || request.activationReplicas() > request.maxReplicas()
        || request.prewarmNodes() < 0
        || request.prewarmNodes() > request.maxReplicas()) {
      throw new IllegalArgumentException(
          "Runtime pool replica policy is invalid"
      );
    }
    if ((request.idleTimeoutSeconds() != 0
        && (request.idleTimeoutSeconds() < 30
        || request.idleTimeoutSeconds() > 86_400))
        || request.replicaLifetimeSeconds() < 60
        || request.replicaLifetimeSeconds() > 86_400) {
      throw new IllegalArgumentException(
          "Runtime pool lifecycle policy is invalid"
      );
    }
    String networkMode = request.networkMode() == null
        ? "none" : request.networkMode().trim().toLowerCase();
    if (!"none".equals(networkMode)
        && !"bridge".equals(networkMode)
        && !"egress".equals(networkMode)) {
      throw new IllegalArgumentException(
          "Runtime pool network mode is invalid"
      );
    }
    return new NormalizedPool(
        code,
        name,
        serverId,
        imageReference,
        imageDigest,
        memoryBytes,
        nanoCpus,
        pidsLimit,
        networkMode,
        egressPolicyCodec.normalize(networkMode, request.egressAllowlist()),
        secretService.normalizeReferences(
            request.secretIds(),
            scope.scopeType(),
            scope.tenantId()
        ),
        request.minReplicas(),
        request.maxReplicas(),
        request.desiredReplicas(),
        request.activationReplicas(),
        request.prewarmNodes(),
        request.idleTimeoutSeconds(),
        request.replicaLifetimeSeconds()
    );
  }

  private AiRuntimePool newPool(
      final ScopeAssignment scope,
      final String code,
      final Instant now
  ) {
    AiRuntimePool pool = new AiRuntimePool();
    pool.setScopeType(scope.scopeType());
    pool.setTenantId(scope.tenantId());
    pool.setCode(code);
    pool.setCurrentReplicas(0);
    pool.setReadyReplicas(0);
    pool.setPrewarmedNodes(0);
    pool.setNextReplicaSequence(1);
    pool.setLastActivityAt(now);
    return pool;
  }

  private void apply(
      final AiRuntimePool pool,
      final NormalizedPool value
  ) {
    pool.setRuntimeProfileId(null);
    pool.setActiveRevisionId(null);
    pool.setRuntimeSpecJson(null);
    pool.setName(value.name());
    pool.setServerId(value.serverId());
    pool.setImageReference(value.imageReference());
    pool.setImageDigest(value.imageDigest());
    pool.setRequestedMemoryBytes(value.memoryBytes());
    pool.setRequestedNanoCpus(value.nanoCpus());
    pool.setRequestedPidsLimit(value.pidsLimit());
    pool.setNetworkMode(value.networkMode());
    pool.setEgressAllowlistJson(value.egressAllowlistJson());
    pool.setSecretReferencesJson(value.secretReferencesJson());
    pool.setMinReplicas(value.minReplicas());
    pool.setMaxReplicas(value.maxReplicas());
    pool.setDesiredReplicas(value.desiredReplicas());
    pool.setActivationReplicas(value.activationReplicas());
    pool.setPrewarmNodes(value.prewarmNodes());
    pool.setIdleTimeoutSeconds(value.idleTimeoutSeconds());
    pool.setReplicaLifetimeSeconds(value.replicaLifetimeSeconds());
  }

  private boolean runtimeSpecChanged(
      final AiRuntimePool pool,
      final NormalizedPool value
  ) {
    return !Objects.equals(pool.getImageReference(), value.imageReference())
        || !Objects.equals(pool.getImageDigest(), value.imageDigest())
        || pool.getRequestedMemoryBytes() != value.memoryBytes()
        || pool.getRequestedNanoCpus() != value.nanoCpus()
        || pool.getRequestedPidsLimit() != value.pidsLimit()
        || !Objects.equals(pool.getNetworkMode(), value.networkMode())
        || !Objects.equals(
            pool.getEgressAllowlistJson(),
            value.egressAllowlistJson()
        )
        || !Objects.equals(
            pool.getSecretReferencesJson(),
            value.secretReferencesJson()
        )
        || pool.getReplicaLifetimeSeconds()
            != value.replicaLifetimeSeconds();
  }

  private void requestReplicaReplacement(
      final AiRuntimePool pool,
      final Instant now,
      final String reason
  ) {
    workloadRepository.findActiveByPool(pool.getId()).stream()
        .filter(workload -> !isTerminal(workload.getStatus()))
        .forEach(workload -> {
          if (workload.getStatus() == RuntimeWorkloadStatus.PENDING
              && workload.getLeaseId() == null) {
            workload.setStatus(RuntimeWorkloadStatus.CANCELLED);
            workload.setFinishedAt(now);
          } else {
            workload.setStatus(RuntimeWorkloadStatus.STOPPING);
            workload.setLastObservedAt(null);
          }
          workload.setLastError(reason);
          workloadRepository.save(workload);
        });
  }

  private boolean isTerminal(final RuntimeWorkloadStatus status) {
    return status == RuntimeWorkloadStatus.SUCCEEDED
        || status == RuntimeWorkloadStatus.FAILED
        || status == RuntimeWorkloadStatus.LOST
        || status == RuntimeWorkloadStatus.CANCELLED;
  }

  private AiRuntimePool decorate(final AiRuntimePool pool) {
    pool.setEgressAllowlist(egressPolicyCodec.decode(
        pool.getNetworkMode(),
        pool.getEgressAllowlistJson()
    ));
    pool.setSecretIds(secretService.references(
        pool.getSecretReferencesJson()
    ));
    return pool;
  }

  private String normalizeImageReference(final String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty()
        || normalized.length() > 440
        || normalized.contains("@")
        || normalized.chars().anyMatch(Character::isWhitespace)
        || normalized.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("OCI image reference is invalid");
    }
    return normalized;
  }

  private String resolveImageDigest(
      final String imageReference,
      final String value
  ) {
    String requested = value == null ? "" : value.trim().toLowerCase();
    if (!requested.isEmpty()) {
      if (!DIGEST.matcher(requested).matches()) {
        throw new IllegalArgumentException("OCI image digest is invalid");
      }
      return requested;
    }
    final RuntimeImageResolution resolution;
    try {
      resolution = imageResolver.resolve(imageReference);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException(
          "OCI image tag could not be resolved: " + safeMessage(ex),
          ex
      );
    }
    String resolved = resolution == null || resolution.imageDigest() == null
        ? "" : resolution.imageDigest().trim().toLowerCase();
    if (!DIGEST.matcher(resolved).matches()) {
      throw new IllegalArgumentException(
          "OCI image tag did not resolve to a sha256 digest"
      );
    }
    return resolved;
  }

  private String safeMessage(final RuntimeException error) {
    String message = error.getMessage();
    String value = message == null || message.isBlank()
        ? error.getClass().getSimpleName() : message.trim();
    return value.length() <= 768 ? value : value.substring(0, 768);
  }

  private long defaulted(final long requested, final Long fallback) {
    if (requested != 0) {
      return requested;
    }
    if (fallback == null || fallback <= 0) {
      throw new IllegalStateException(
          "Runtime workload defaults are not configured"
      );
    }
    return fallback;
  }

  private String requireIdentifier(final String value, final String field) {
    String normalized = value == null ? "" : value.trim();
    if (!IDENTIFIER.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private String requireText(
      final String value,
      final String field,
      final int maxLength
  ) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maxLength) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private record NormalizedPool(
      String code,
      String name,
      String serverId,
      String imageReference,
      String imageDigest,
      long memoryBytes,
      long nanoCpus,
      long pidsLimit,
      String networkMode,
      String egressAllowlistJson,
      String secretReferencesJson,
      int minReplicas,
      int maxReplicas,
      int desiredReplicas,
      int activationReplicas,
      int prewarmNodes,
      long idleTimeoutSeconds,
      long replicaLifetimeSeconds
  ) {
  }
}
