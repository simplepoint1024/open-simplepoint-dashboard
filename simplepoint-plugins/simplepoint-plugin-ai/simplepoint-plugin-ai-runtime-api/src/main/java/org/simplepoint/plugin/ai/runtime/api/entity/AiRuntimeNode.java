package org.simplepoint.plugin.ai.runtime.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
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
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.runtime.api.model.AiRuntimeErrorCodeSerializer;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;

/**
 * Platform-owned execution node running one independent Tool Runtime process.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_runtime_nodes",
    indexes = {
        @Index(
            name = "uk_simpoint_ai_runtime_node_id",
            columnList = "node_id",
            unique = true
        ),
        @Index(name = "idx_simpoint_ai_runtime_node_status", columnList = "status"),
        @Index(
            name = "idx_simpoint_ai_runtime_node_heartbeat",
            columnList = "heartbeat_expires_at"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI OCI Runtime Node")
public class AiRuntimeNode extends BaseEntityImpl<String> {

  @Column(name = "node_id", length = 64, nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private String nodeId;

  @Column(name = "instance_id", length = 128, nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private String instanceId;

  @Column(nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private long generation;

  @Column(name = "display_name", length = 128, nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private String displayName;

  @Column(name = "advertise_url", length = 2048, nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private String advertiseUrl;

  @Column(name = "runtime_version", length = 64, nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private String runtimeVersion;

  @Column(name = "engine_api_version", length = 32, nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private String engineApiVersion;

  @Column(name = "engine_os_type", length = 32, nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private String engineOsType;

  @Column(name = "cpu_cores", nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private int cpuCores;

  @Column(name = "memory_bytes", nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private long memoryBytes;

  @Column(name = "max_workloads", nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private int maxWorkloads;

  @Column(name = "running_workloads", nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private int runningWorkloads;

  @Column(
      name = "max_workload_memory_bytes",
      nullable = false,
      columnDefinition = "BIGINT DEFAULT 2147483648"
  )
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private long maxWorkloadMemoryBytes;

  @Column(
      name = "max_workload_nano_cpus",
      nullable = false,
      columnDefinition = "BIGINT DEFAULT 4000000000"
  )
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private long maxWorkloadNanoCpus;

  @Column(
      name = "max_workload_pids_limit",
      nullable = false,
      columnDefinition = "BIGINT DEFAULT 512"
  )
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private long maxWorkloadPidsLimit;

  @Column(name = "require_image_digest", nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private boolean requireImageDigest;

  @Column(name = "require_mcp_labels", nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private boolean requireMcpLabels;

  @Column(name = "allow_bridge_network", nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private boolean allowBridgeNetwork;

  @Column(
      name = "allow_egress_network",
      nullable = false,
      columnDefinition = "BOOLEAN DEFAULT FALSE"
  )
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private boolean allowEgressNetwork;

  @Column(
      name = "require_supply_chain_admission",
      nullable = false,
      columnDefinition = "BOOLEAN DEFAULT FALSE"
  )
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private boolean requireSupplyChainAdmission;

  @Column(
      name = "seccomp_enforced",
      nullable = false,
      columnDefinition = "BOOLEAN DEFAULT FALSE"
  )
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private boolean seccompEnforced;

  @Column(name = "seccomp_profile_hash", length = 71)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private String seccompProfileHash;

  @Column(
      name = "apparmor_enforced",
      nullable = false,
      columnDefinition = "BOOLEAN DEFAULT FALSE"
  )
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private boolean appArmorEnforced;

  @Column(name = "apparmor_profile", length = 128)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private String appArmorProfile;

  @JsonIgnore
  @Column(name = "labels_json", columnDefinition = "TEXT", nullable = false)
  @Schema(hidden = true)
  private String labelsJson;

  @JsonIgnore
  @Column(
      name = "cached_image_digests_json",
      columnDefinition = "TEXT DEFAULT '[]'",
      nullable = false
  )
  @Schema(hidden = true)
  private String cachedImageDigestsJson;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Map<String, String> labels;

  @Transient
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private List<String> cachedImageDigests;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private RuntimeNodeStatus status;

  @Column(name = "registered_at", nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Instant registeredAt;

  @Column(name = "last_heartbeat_at", nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Instant lastHeartbeatAt;

  @Column(name = "heartbeat_expires_at", nullable = false)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private Instant heartbeatExpiresAt;

  @Column(name = "last_error", length = 1024)
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  @JsonSerialize(using = AiRuntimeErrorCodeSerializer.class)
  private String lastError;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
