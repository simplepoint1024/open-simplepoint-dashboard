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
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolStatus;

/**
 * Durable desired and observed state for a reusable OCI MCP replica pool.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_runtime_pools",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_runtime_pool_scope",
            columnList = "scope_type, tenant_id"
        ),
        @Index(
            name = "idx_simpoint_ai_runtime_pool_status",
            columnList = "status"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI OCI Runtime Pool")
public class AiRuntimePool extends BaseEntityImpl<String> {

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(length = 64, nullable = false)
  private String code;

  @Column(length = 128, nullable = false)
  private String name;

  @Column(name = "server_id", length = 64, nullable = false)
  private String serverId;

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

  @Column(name = "min_replicas", nullable = false)
  private int minReplicas;

  @Column(name = "max_replicas", nullable = false)
  private int maxReplicas;

  @Column(name = "desired_replicas", nullable = false)
  private int desiredReplicas;

  @Column(name = "activation_replicas", nullable = false)
  private int activationReplicas;

  @Column(name = "prewarm_nodes", nullable = false)
  private int prewarmNodes;

  @Column(name = "idle_timeout_seconds", nullable = false)
  private long idleTimeoutSeconds;

  @Column(name = "replica_lifetime_seconds", nullable = false)
  private long replicaLifetimeSeconds;

  @Column(name = "current_replicas", nullable = false)
  private int currentReplicas;

  @Column(name = "ready_replicas", nullable = false)
  private int readyReplicas;

  @Column(name = "prewarmed_nodes", nullable = false)
  private int prewarmedNodes;

  @Column(name = "next_replica_sequence", nullable = false)
  private long nextReplicaSequence;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private RuntimePoolStatus status;

  @Column(name = "last_activity_at", nullable = false)
  private Instant lastActivityAt;

  @Column(name = "last_reconciled_at")
  private Instant lastReconciledAt;

  @Column(name = "last_prewarm_at")
  private Instant lastPrewarmAt;

  @Column(name = "last_error", length = 1024)
  private String lastError;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
