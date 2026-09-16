package org.simplepoint.plugin.ai.runtime.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeControlResponse;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeFencedException;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeHeartbeat;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeNotRegisteredException;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeOfflineRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeRegistration;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeNodeService;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeImageCacheCodec;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Transactional runtime-node registry with process-generation fencing.
 */
@Service
public class AiRuntimeNodeServiceImpl implements AiRuntimeNodeService {

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private static final Pattern LABEL_KEY =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$");

  private static final Pattern SHA256 =
      Pattern.compile("^sha256:[a-f0-9]{64}$");

  private static final int MAX_LABELS = 64;

  private static final TypeReference<Map<String, String>> STRING_MAP =
      new TypeReference<>() {
      };

  private static final long MINIMUM_MEMORY_BYTES = 32L * 1024 * 1024;

  private static final List<RuntimeNodeStatus> EXPIRABLE_STATUSES = List.of(
      RuntimeNodeStatus.REGISTERING,
      RuntimeNodeStatus.READY,
      RuntimeNodeStatus.DRAINING,
      RuntimeNodeStatus.ERROR
  );

  private final AiRuntimeNodeRepository repository;

  private final AiRuntimeProperties properties;

  private final ObjectMapper objectMapper;

  private final AiRuntimeImageCacheCodec imageCacheCodec;

  /**
   * Creates the runtime-node control-plane service.
   */
  public AiRuntimeNodeServiceImpl(
      final AiRuntimeNodeRepository repository,
      final AiRuntimeProperties properties,
      final ObjectMapper objectMapper,
      final AiRuntimeImageCacheCodec imageCacheCodec
  ) {
    this.repository = repository;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.imageCacheCodec = imageCacheCodec;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public RuntimeNodeControlResponse register(
      final String nodeId,
      final RuntimeNodeRegistration registration
  ) {
    String normalizedNodeId = requireIdentifier(nodeId, "Runtime node ID");
    RuntimeNodeRegistration normalized = normalizeRegistration(registration);
    final Instant now = Instant.now();
    AiRuntimeNode node = repository.findActiveByNodeIdForUpdate(normalizedNodeId)
        .orElseGet(() -> newNode(normalizedNodeId, now));
    boolean newGeneration = !normalized.instanceId().equals(node.getInstanceId());
    if (newGeneration) {
      node.setGeneration(nextGeneration(node.getGeneration()));
      node.setRegisteredAt(now);
    }
    node.setInstanceId(normalized.instanceId());
    node.setDisplayName(normalized.displayName());
    node.setAdvertiseUrl(normalized.advertiseUrl());
    node.setRuntimeVersion(normalized.runtimeVersion());
    applyCapacity(node, normalized);
    node.setRequireImageDigest(normalized.requireImageDigest());
    node.setRequireMcpLabels(normalized.requireMcpLabels());
    node.setAllowBridgeNetwork(normalized.allowBridgeNetwork());
    node.setAllowEgressNetwork(normalized.allowEgressNetwork());
    node.setRequireSupplyChainAdmission(
        normalized.requireSupplyChainAdmission()
    );
    node.setSeccompEnforced(normalized.seccompEnforced());
    node.setSeccompProfileHash(normalized.seccompProfileHash());
    node.setAppArmorEnforced(normalized.appArmorEnforced());
    node.setAppArmorProfile(normalized.appArmorProfile());
    node.setLabelsJson(labelsJson(normalized.labels()));
    node.setCachedImageDigestsJson(
        imageCacheCodec.encode(normalized.cachedImageDigests())
    );
    if (newGeneration || node.getStatus() != RuntimeNodeStatus.DRAINING) {
      node.setStatus(RuntimeNodeStatus.READY);
    }
    node.setLastHeartbeatAt(now);
    node.setHeartbeatExpiresAt(now.plus(heartbeatTimeout()));
    node.setLastError(null);
    return response(repository.save(node), now);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public RuntimeNodeControlResponse heartbeat(
      final String nodeId,
      final RuntimeNodeHeartbeat heartbeat
  ) {
    String normalizedNodeId = requireIdentifier(nodeId, "Runtime node ID");
    RuntimeNodeHeartbeat normalized = normalizeHeartbeat(heartbeat);
    AiRuntimeNode node = requireCurrentGeneration(
        normalizedNodeId,
        normalized.instanceId()
    );
    final Instant now = Instant.now();
    node.setEngineApiVersion(normalized.engineApiVersion());
    node.setEngineOsType(normalized.engineOsType());
    node.setCpuCores(normalized.cpuCores());
    node.setMemoryBytes(normalized.memoryBytes());
    node.setMaxWorkloads(normalized.maxWorkloads());
    node.setRunningWorkloads(normalized.runningWorkloads());
    node.setCachedImageDigestsJson(
        imageCacheCodec.encode(normalized.cachedImageDigests())
    );
    node.setMaxWorkloadMemoryBytes(normalized.maxWorkloadMemoryBytes());
    node.setMaxWorkloadNanoCpus(normalized.maxWorkloadNanoCpus());
    node.setMaxWorkloadPidsLimit(normalized.maxWorkloadPidsLimit());
    if (node.getStatus() != RuntimeNodeStatus.DRAINING) {
      node.setStatus(normalized.healthy()
          ? RuntimeNodeStatus.READY : RuntimeNodeStatus.ERROR);
    }
    node.setLastHeartbeatAt(now);
    node.setHeartbeatExpiresAt(now.plus(heartbeatTimeout()));
    node.setLastError(safeError(normalized.error()));
    return response(repository.save(node), now);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public RuntimeNodeControlResponse offline(
      final String nodeId,
      final RuntimeNodeOfflineRequest request
  ) {
    String normalizedNodeId = requireIdentifier(nodeId, "Runtime node ID");
    String instanceId = requireText(
        request == null ? null : request.instanceId(),
        "Runtime instance ID",
        128
    );
    AiRuntimeNode node = requireCurrentGeneration(normalizedNodeId, instanceId);
    final Instant now = Instant.now();
    node.setStatus(RuntimeNodeStatus.OFFLINE);
    node.setLastHeartbeatAt(now);
    node.setHeartbeatExpiresAt(now);
    node.setLastError("Runtime node shut down gracefully");
    return response(repository.save(node), now);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiRuntimeNode> findActiveByNodeId(final String nodeId) {
    return repository.findActiveByNodeId(
        requireIdentifier(nodeId, "Runtime node ID")
    ).map(this::decorate);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiRuntimeNode> findAll(final Pageable pageable) {
    Page<AiRuntimeNode> result = repository.findAllActive(pageable);
    result.getContent().forEach(this::decorate);
    return result;
  }

  @Override
  @Scheduled(
      fixedDelayString = "${simplepoint.ai.runtime.stale-scan-interval:10s}"
  )
  @Transactional(rollbackFor = Exception.class)
  public int expireStaleNodes() {
    if (!Boolean.TRUE.equals(properties.getSchedulingEnabled())) {
      return 0;
    }
    final Instant now = Instant.now();
    List<AiRuntimeNode> expired = new ArrayList<>(
        repository.findExpiredNodes(now, EXPIRABLE_STATUSES)
    );
    expired.forEach(node -> {
      node.setStatus(RuntimeNodeStatus.OFFLINE);
      node.setLastError("Runtime node heartbeat lease expired");
    });
    if (!expired.isEmpty()) {
      repository.saveAll(expired);
    }
    return expired.size();
  }

  private AiRuntimeNode requireCurrentGeneration(
      final String nodeId,
      final String instanceId
  ) {
    AiRuntimeNode node = repository.findActiveByNodeIdForUpdate(nodeId)
        .orElseThrow(() -> new RuntimeNodeNotRegisteredException(
            "Runtime node must register before sending heartbeats"
        ));
    if (!node.getInstanceId().equals(instanceId)) {
      throw new RuntimeNodeFencedException(
          "Runtime node process generation has been fenced"
      );
    }
    return node;
  }

  private RuntimeNodeRegistration normalizeRegistration(
      final RuntimeNodeRegistration value
  ) {
    if (value == null) {
      throw new IllegalArgumentException("Runtime registration is required");
    }
    String instanceId = requireText(
        value.instanceId(),
        "Runtime instance ID",
        128
    );
    String displayName = requireText(
        value.displayName(),
        "Runtime display name",
        128
    );
    String advertiseUrl = normalizeAdvertiseUrl(value.advertiseUrl());
    String runtimeVersion = requireText(
        value.runtimeVersion(),
        "Runtime version",
        64
    );
    validateCapacity(
        value.cpuCores(),
        value.memoryBytes(),
        value.maxWorkloads(),
        value.runningWorkloads(),
        value.maxWorkloadMemoryBytes(),
        value.maxWorkloadNanoCpus(),
        value.maxWorkloadPidsLimit()
    );
    return new RuntimeNodeRegistration(
        instanceId,
        displayName,
        advertiseUrl,
        runtimeVersion,
        requireText(value.engineApiVersion(), "Engine API version", 32),
        requireText(value.engineOsType(), "Engine OS type", 32),
        value.cpuCores(),
        value.memoryBytes(),
        value.maxWorkloads(),
        value.runningWorkloads(),
        imageCacheCodec.decode(imageCacheCodec.encode(
            value.cachedImageDigests()
        )),
        value.maxWorkloadMemoryBytes(),
        value.maxWorkloadNanoCpus(),
        value.maxWorkloadPidsLimit(),
        value.requireImageDigest(),
        value.requireMcpLabels(),
        value.allowBridgeNetwork(),
        value.allowEgressNetwork(),
        value.requireSupplyChainAdmission(),
        value.seccompEnforced(),
        normalizeSeccompHash(
            value.seccompEnforced(),
            value.seccompProfileHash()
        ),
        value.appArmorEnforced(),
        normalizeAppArmorProfile(
            value.appArmorEnforced(),
            value.appArmorProfile()
        ),
        normalizeLabels(value.labels())
    );
  }

  private static String normalizeSeccompHash(
      final boolean enforced,
      final String value
  ) {
    String normalized = value == null ? null : value.trim();
    if (!enforced) {
      return null;
    }
    if (normalized == null || !SHA256.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Runtime seccomp profile hash is invalid");
    }
    return normalized;
  }

  private static String normalizeAppArmorProfile(
      final boolean enforced,
      final String value
  ) {
    String normalized = value == null ? null : value.trim();
    if (!enforced) {
      return null;
    }
    if (normalized == null || normalized.length() > 128
        || !LABEL_KEY.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Runtime AppArmor profile is invalid");
    }
    return normalized;
  }

  private RuntimeNodeHeartbeat normalizeHeartbeat(
      final RuntimeNodeHeartbeat value
  ) {
    if (value == null) {
      throw new IllegalArgumentException("Runtime heartbeat is required");
    }
    validateCapacity(
        value.cpuCores(),
        value.memoryBytes(),
        value.maxWorkloads(),
        value.runningWorkloads(),
        value.maxWorkloadMemoryBytes(),
        value.maxWorkloadNanoCpus(),
        value.maxWorkloadPidsLimit()
    );
    return new RuntimeNodeHeartbeat(
        requireText(value.instanceId(), "Runtime instance ID", 128),
        requireText(value.engineApiVersion(), "Engine API version", 32),
        requireText(value.engineOsType(), "Engine OS type", 32),
        value.cpuCores(),
        value.memoryBytes(),
        value.maxWorkloads(),
        value.runningWorkloads(),
        imageCacheCodec.decode(imageCacheCodec.encode(
            value.cachedImageDigests()
        )),
        value.maxWorkloadMemoryBytes(),
        value.maxWorkloadNanoCpus(),
        value.maxWorkloadPidsLimit(),
        value.healthy(),
        safeError(value.error())
    );
  }

  private void applyCapacity(
      final AiRuntimeNode node,
      final RuntimeNodeRegistration registration
  ) {
    node.setEngineApiVersion(registration.engineApiVersion());
    node.setEngineOsType(registration.engineOsType());
    node.setCpuCores(registration.cpuCores());
    node.setMemoryBytes(registration.memoryBytes());
    node.setMaxWorkloads(registration.maxWorkloads());
    node.setRunningWorkloads(registration.runningWorkloads());
    node.setMaxWorkloadMemoryBytes(registration.maxWorkloadMemoryBytes());
    node.setMaxWorkloadNanoCpus(registration.maxWorkloadNanoCpus());
    node.setMaxWorkloadPidsLimit(registration.maxWorkloadPidsLimit());
  }

  private void validateCapacity(
      final int cpuCores,
      final long memoryBytes,
      final int maxWorkloads,
      final int runningWorkloads,
      final long maxWorkloadMemoryBytes,
      final long maxWorkloadNanoCpus,
      final long maxWorkloadPidsLimit
  ) {
    if (cpuCores <= 0 || cpuCores > 65_536) {
      throw new IllegalArgumentException("Runtime CPU capacity is invalid");
    }
    if (memoryBytes < MINIMUM_MEMORY_BYTES) {
      throw new IllegalArgumentException("Runtime memory capacity is invalid");
    }
    if (maxWorkloads <= 0 || maxWorkloads > 10_000) {
      throw new IllegalArgumentException("Runtime workload capacity is invalid");
    }
    if (runningWorkloads < 0 || runningWorkloads > maxWorkloads) {
      throw new IllegalArgumentException("Runtime running workload count is invalid");
    }
    if (maxWorkloadMemoryBytes < MINIMUM_MEMORY_BYTES
        || maxWorkloadMemoryBytes > memoryBytes
        || maxWorkloadNanoCpus <= 0
        || maxWorkloadNanoCpus > (long) cpuCores * 1_000_000_000L
        || maxWorkloadPidsLimit <= 0
        || maxWorkloadPidsLimit > 4096) {
      throw new IllegalArgumentException(
          "Runtime per-workload capacity is invalid"
      );
    }
  }

  private Map<String, String> normalizeLabels(final Map<String, String> labels) {
    if (labels == null || labels.isEmpty()) {
      return Map.of();
    }
    if (labels.size() > MAX_LABELS) {
      throw new IllegalArgumentException("Runtime node has too many labels");
    }
    Map<String, String> normalized = new TreeMap<>();
    labels.forEach((key, value) -> {
      String normalizedKey = key == null ? "" : key.trim();
      String normalizedValue = value == null ? "" : value.trim();
      if (!LABEL_KEY.matcher(normalizedKey).matches()
          || normalizedValue.length() > 512) {
        throw new IllegalArgumentException("Runtime node label is invalid");
      }
      normalized.put(normalizedKey, normalizedValue);
    });
    return Map.copyOf(normalized);
  }

  private String labelsJson(final Map<String, String> labels) {
    try {
      return objectMapper.writeValueAsString(labels);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Runtime node labels cannot be encoded", ex);
    }
  }

  private AiRuntimeNode decorate(final AiRuntimeNode node) {
    node.setCachedImageDigests(imageCacheCodec.decode(
        node.getCachedImageDigestsJson()
    ));
    try {
      String labelsJson = node.getLabelsJson();
      Map<String, String> labels = objectMapper.readValue(
          labelsJson == null || labelsJson.isBlank() ? "{}" : labelsJson,
          STRING_MAP
      );
      node.setLabels(normalizeLabels(labels));
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      throw new IllegalStateException("Runtime node labels are invalid", ex);
    }
    return node;
  }

  private String normalizeAdvertiseUrl(final String value) {
    String normalized = requireText(value, "Runtime advertise URL", 2048);
    URI uri;
    try {
      uri = URI.create(normalized);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Runtime advertise URL is invalid", ex);
    }
    if (!List.of("http", "https").contains(uri.getScheme())
        || !StringUtils.hasText(uri.getHost())
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException("Runtime advertise URL is invalid");
    }
    String path = uri.getPath();
    if (StringUtils.hasText(path) && !"/".equals(path)) {
      throw new IllegalArgumentException(
          "Runtime advertise URL must not contain a path"
      );
    }
    return normalized.endsWith("/")
        ? normalized.substring(0, normalized.length() - 1) : normalized;
  }

  private Duration heartbeatTimeout() {
    Duration interval = properties.getHeartbeatInterval();
    Duration timeout = properties.getHeartbeatTimeout();
    if (interval == null || interval.isNegative() || interval.isZero()
        || timeout == null || timeout.compareTo(interval) <= 0) {
      throw new IllegalStateException("Runtime heartbeat configuration is invalid");
    }
    return timeout;
  }

  private RuntimeNodeControlResponse response(
      final AiRuntimeNode node,
      final Instant acceptedAt
  ) {
    heartbeatTimeout();
    return new RuntimeNodeControlResponse(
        node.getNodeId(),
        node.getInstanceId(),
        node.getGeneration(),
        node.getStatus(),
        acceptedAt,
        properties.getHeartbeatInterval().toSeconds(),
        properties.getHeartbeatTimeout().toSeconds()
    );
  }

  private AiRuntimeNode newNode(final String nodeId, final Instant now) {
    AiRuntimeNode node = new AiRuntimeNode();
    node.setNodeId(nodeId);
    node.setGeneration(0);
    node.setInstanceId("");
    node.setCachedImageDigestsJson("[]");
    node.setStatus(RuntimeNodeStatus.REGISTERING);
    node.setRegisteredAt(now);
    node.setLastHeartbeatAt(now);
    node.setHeartbeatExpiresAt(now);
    return node;
  }

  private long nextGeneration(final long current) {
    try {
      return Math.addExact(current, 1L);
    } catch (ArithmeticException ex) {
      throw new IllegalStateException("Runtime node generation is exhausted", ex);
    }
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

  private String safeError(final String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim();
    return normalized.length() <= 1024
        ? normalized : normalized.substring(0, 1024);
  }
}
