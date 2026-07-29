package org.simplepoint.plugin.ai.runtime.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadSubmitRequest;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeWorkloadRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSecretService;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeWorkloadService;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeEgressPolicyCodec;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-aware durable OCI workload submission service.
 */
@Service
public class AiRuntimeWorkloadServiceImpl
    implements AiRuntimeWorkloadService {

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private static final Pattern DIGEST =
      Pattern.compile("^sha256:[a-f0-9]{64}$");

  private static final long MINIMUM_MEMORY_BYTES = 32L * 1024 * 1024;

  private static final long MAXIMUM_MEMORY_BYTES = 16L * 1024 * 1024 * 1024;

  private static final long MAXIMUM_NANO_CPUS = 16_000_000_000L;

  private static final long MAXIMUM_PIDS = 4096L;

  private static final long MAXIMUM_TIMEOUT_SECONDS = 24L * 60 * 60;

  private final AiRuntimeWorkloadRepository repository;

  private final AiRuntimeProperties properties;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiRuntimeSecretService secretService;

  private final AiRuntimeEgressPolicyCodec egressPolicyCodec;

  /**
   * Creates the workload submission service.
   */
  public AiRuntimeWorkloadServiceImpl(
      final AiRuntimeWorkloadRepository repository,
      final AiRuntimeProperties properties,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiRuntimeSecretService secretService,
      final AiRuntimeEgressPolicyCodec egressPolicyCodec
  ) {
    this.repository = repository;
    this.properties = properties;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.secretService = secretService;
    this.egressPolicyCodec = egressPolicyCodec;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimeWorkload submit(
      final RuntimeWorkloadSubmitRequest request
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    NormalizedSubmission normalized = normalize(request, scope);
    Optional<AiRuntimeWorkload> existing =
        repository.findActiveByExecutionIdForUpdate(normalized.executionId());
    if (existing.isPresent()) {
      AiRuntimeWorkload workload = existing.orElseThrow();
      scopeAccessPolicy.assertCanManageOwnedResource(
          workload.getScopeType(),
          workload.getTenantId()
      );
      if (!sameSubmission(workload, normalized)) {
        throw new IllegalArgumentException(
            "Runtime execution ID already owns a different workload"
        );
      }
      return decorate(workload);
    }

    Instant now = Instant.now();
    AiRuntimeWorkload workload = new AiRuntimeWorkload();
    workload.setScopeType(scope.scopeType());
    workload.setTenantId(scope.tenantId());
    workload.setExecutionId(normalized.executionId());
    workload.setServerId(normalized.serverId());
    workload.setImageReference(normalized.imageReference());
    workload.setImageDigest(normalized.imageDigest());
    workload.setRequestedMemoryBytes(normalized.memoryBytes());
    workload.setRequestedNanoCpus(normalized.nanoCpus());
    workload.setRequestedPidsLimit(normalized.pidsLimit());
    workload.setTimeoutSeconds(normalized.timeoutSeconds());
    workload.setNetworkMode(normalized.networkMode());
    workload.setEgressAllowlistJson(normalized.egressAllowlistJson());
    workload.setSecretReferencesJson(normalized.secretReferencesJson());
    workload.setStatus(RuntimeWorkloadStatus.PENDING);
    workload.setFencingToken(0);
    workload.setDeadlineAt(now.plusSeconds(normalized.timeoutSeconds()));
    return decorate(repository.save(workload));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiRuntimeWorkload> findAll(final Pageable pageable) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    Page<AiRuntimeWorkload> result = repository.findAllActiveByScope(
        scope.scopeType(),
        scope.tenantId(),
        pageable
    );
    result.getContent().forEach(this::decorate);
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiRuntimeWorkload> find(final String workloadId) {
    Optional<AiRuntimeWorkload> result =
        repository.findById(requireIdentifier(workloadId, "Runtime workload ID"));
    result.ifPresent(workload -> scopeAccessPolicy.assertCanReadManagedResource(
        workload.getScopeType(),
        workload.getTenantId()
    ));
    return result.map(this::decorate);
  }

  @Override
  @Transactional(readOnly = true)
  public List<AiRuntimeWorkload> findByPool(final String poolId) {
    List<AiRuntimeWorkload> result = repository.findActiveByPool(
        requireIdentifier(poolId, "Runtime pool ID")
    );
    result.forEach(workload ->
        scopeAccessPolicy.assertCanReadManagedResource(
            workload.getScopeType(),
            workload.getTenantId()
        )
    );
    result.forEach(this::decorate);
    return List.copyOf(result);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimeWorkload stop(final String workloadId) {
    AiRuntimeWorkload workload = repository.findActiveByIdForUpdate(
        requireIdentifier(workloadId, "Runtime workload ID")
    ).orElseThrow(() -> new IllegalArgumentException(
        "Runtime workload does not exist"
    ));
    scopeAccessPolicy.assertCanManageOwnedResource(
        workload.getScopeType(),
        workload.getTenantId()
    );
    if (isTerminal(workload.getStatus())) {
      return decorate(workload);
    }
    Instant now = Instant.now();
    if (workload.getStatus() == RuntimeWorkloadStatus.PENDING
        && workload.getLeaseId() == null) {
      workload.setStatus(RuntimeWorkloadStatus.CANCELLED);
      workload.setFinishedAt(now);
    } else {
      workload.setStatus(RuntimeWorkloadStatus.STOPPING);
      workload.setLastObservedAt(null);
    }
    workload.setLastError("Runtime workload cancellation requested");
    return decorate(repository.save(workload));
  }

  private AiRuntimeWorkload decorate(final AiRuntimeWorkload workload) {
    workload.setEgressAllowlist(egressPolicyCodec.decode(
        workload.getNetworkMode(),
        workload.getEgressAllowlistJson()
    ));
    workload.setSecretIds(secretService.references(
        workload.getSecretReferencesJson()
    ));
    return workload;
  }

  private NormalizedSubmission normalize(
      final RuntimeWorkloadSubmitRequest request,
      final ScopeAssignment scope
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "Runtime workload submission is required"
      );
    }
    String executionId = request.executionId();
    if (executionId == null || executionId.isBlank()) {
      executionId = UUID.randomUUID().toString();
    }
    executionId = requireIdentifier(executionId, "Runtime execution ID");
    final String serverId =
        requireIdentifier(request.serverId(), "MCP server ID");
    final String imageReference =
        normalizeImageReference(request.imageReference());
    String imageDigest = request.imageDigest() == null
        ? "" : request.imageDigest().trim().toLowerCase();
    if (!DIGEST.matcher(imageDigest).matches()) {
      throw new IllegalArgumentException("OCI image digest is invalid");
    }
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
        || memoryBytes > MAXIMUM_MEMORY_BYTES) {
      throw new IllegalArgumentException("Runtime workload memory is invalid");
    }
    if (nanoCpus <= 0 || nanoCpus > MAXIMUM_NANO_CPUS) {
      throw new IllegalArgumentException("Runtime workload CPU is invalid");
    }
    if (pidsLimit <= 0 || pidsLimit > MAXIMUM_PIDS) {
      throw new IllegalArgumentException(
          "Runtime workload process limit is invalid"
      );
    }
    if (request.timeoutSeconds() <= 0
        || request.timeoutSeconds() > MAXIMUM_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException("Runtime workload timeout is invalid");
    }
    String networkMode = request.networkMode() == null
        ? "none" : request.networkMode().trim().toLowerCase();
    if (!"none".equals(networkMode)
        && !"bridge".equals(networkMode)
        && !"egress".equals(networkMode)) {
      throw new IllegalArgumentException(
          "Runtime workload network mode is invalid"
      );
    }
    String egressAllowlistJson = egressPolicyCodec.normalize(
        networkMode,
        request.egressAllowlist()
    );
    String secretReferencesJson = secretService.normalizeReferences(
        request.secretIds(),
        scope.scopeType(),
        scope.tenantId()
    );
    return new NormalizedSubmission(
        executionId,
        serverId,
        imageReference,
        imageDigest,
        memoryBytes,
        nanoCpus,
        pidsLimit,
        request.timeoutSeconds(),
        networkMode,
        egressAllowlistJson,
        secretReferencesJson
    );
  }

  private boolean sameSubmission(
      final AiRuntimeWorkload workload,
      final NormalizedSubmission normalized
  ) {
    return Objects.equals(workload.getServerId(), normalized.serverId())
        && Objects.equals(
            workload.getImageReference(),
            normalized.imageReference()
        )
        && Objects.equals(workload.getImageDigest(), normalized.imageDigest())
        && workload.getRequestedMemoryBytes() == normalized.memoryBytes()
        && workload.getRequestedNanoCpus() == normalized.nanoCpus()
        && workload.getRequestedPidsLimit() == normalized.pidsLimit()
        && workload.getTimeoutSeconds() == normalized.timeoutSeconds()
        && Objects.equals(workload.getNetworkMode(), normalized.networkMode())
        && Objects.equals(
            workload.getEgressAllowlistJson(),
            normalized.egressAllowlistJson()
        )
        && Objects.equals(
            workload.getSecretReferencesJson(),
            normalized.secretReferencesJson()
        );
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

  private boolean isTerminal(final RuntimeWorkloadStatus status) {
    return status == RuntimeWorkloadStatus.SUCCEEDED
        || status == RuntimeWorkloadStatus.FAILED
        || status == RuntimeWorkloadStatus.LOST
        || status == RuntimeWorkloadStatus.CANCELLED;
  }

  private record NormalizedSubmission(
      String executionId,
      String serverId,
      String imageReference,
      String imageDigest,
      long memoryBytes,
      long nanoCpus,
      long pidsLimit,
      long timeoutSeconds,
      String networkMode,
      String egressAllowlistJson,
      String secretReferencesJson
  ) {
  }
}
