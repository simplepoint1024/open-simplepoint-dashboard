package org.simplepoint.plugin.ai.runtime.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeLeaseStatus;

/**
 * Renewable workload ownership lease carrying a monotonically increasing fence.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_runtime_leases",
    indexes = {
        @Index(
            name = "idx_simpoint_ai_runtime_lease_workload",
            columnList = "workload_id"
        ),
        @Index(
            name = "idx_simpoint_ai_runtime_lease_node",
            columnList = "node_id"
        ),
        @Index(
            name = "idx_simpoint_ai_runtime_lease_expiry",
            columnList = "status, expires_at"
        )
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(hidden = true)
public class AiRuntimeLease extends BaseEntityImpl<String> {

  @Column(name = "workload_id", length = 64, nullable = false)
  private String workloadId;

  @Column(name = "node_id", length = 64, nullable = false)
  private String nodeId;

  @Column(name = "node_instance_id", length = 128, nullable = false)
  private String nodeInstanceId;

  @Column(name = "fencing_token", nullable = false)
  private long fencingToken;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private RuntimeLeaseStatus status;

  @Column(name = "acquired_at", nullable = false)
  private Instant acquiredAt;

  @Column(name = "renewed_at", nullable = false)
  private Instant renewedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "released_at")
  private Instant releasedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private Long lockVersion;
}
