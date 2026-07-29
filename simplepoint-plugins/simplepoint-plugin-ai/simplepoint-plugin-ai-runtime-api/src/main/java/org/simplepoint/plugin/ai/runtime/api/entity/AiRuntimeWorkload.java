package org.simplepoint.plugin.ai.runtime.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;

/**
 * Durable desired and observed state of one OCI MCP workload.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_runtime_workloads",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_runtime_workload_scope",
            columnList = "scope_type, tenant_id"
        ),
        @Index(
            name = "idx_simpoint_ai_runtime_workload_status",
            columnList = "status"
        ),
        @Index(
            name = "idx_simpoint_ai_runtime_workload_node",
            columnList = "assigned_node_id"
        ),
        @Index(
            name = "idx_simpoint_ai_runtime_workload_pool",
            columnList = "pool_id, status"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(hidden = true)
public class AiRuntimeWorkload extends BaseEntityImpl<String> {

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "execution_id", length = 64, nullable = false)
  private String executionId;

  @Column(name = "server_id", length = 64, nullable = false)
  private String serverId;

  @Column(name = "pool_id", length = 64)
  private String poolId;

  @Column(name = "replica_sequence")
  private Long replicaSequence;

  @Column(name = "image_reference", length = 512, nullable = false)
  private String imageReference;

  @Column(name = "image_digest", length = 71, nullable = false)
  private String imageDigest;

  @Column(name = "requested_memory_bytes", nullable = false)
  private long requestedMemoryBytes;

  @Column(name = "requested_nano_cpus", nullable = false)
  private long requestedNanoCpus;

  @Column(name = "requested_pids_limit", nullable = false)
  private long requestedPidsLimit;

  @Column(name = "timeout_seconds", nullable = false)
  private long timeoutSeconds;

  @Column(name = "network_mode", length = 16, nullable = false)
  private String networkMode;

  @JsonIgnore
  @Column(
      name = "egress_allowlist_json",
      columnDefinition = "TEXT DEFAULT '[]'",
      nullable = false
  )
  private String egressAllowlistJson;

  @JsonIgnore
  @Column(
      name = "secret_references_json",
      columnDefinition = "TEXT DEFAULT '[]'",
      nullable = false
  )
  private String secretReferencesJson;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<String> egressAllowlist;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<String> secretIds;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private RuntimeWorkloadStatus status;

  @Column(name = "assigned_node_id", length = 64)
  private String assignedNodeId;

  @Column(name = "lease_id", length = 64)
  private String leaseId;

  @Column(name = "fencing_token", nullable = false)
  private long fencingToken;

  @Column(name = "runtime_workload_id", length = 64)
  private String runtimeWorkloadId;

  @Column(name = "container_id", length = 128)
  private String containerId;

  @Column(name = "deadline_at", nullable = false)
  private Instant deadlineAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "last_observed_at")
  private Instant lastObservedAt;

  @Column(name = "last_error", length = 1024)
  private String lastError;

  @Version
  @Column(name = "lock_version", nullable = false)
  private Long lockVersion;
}
