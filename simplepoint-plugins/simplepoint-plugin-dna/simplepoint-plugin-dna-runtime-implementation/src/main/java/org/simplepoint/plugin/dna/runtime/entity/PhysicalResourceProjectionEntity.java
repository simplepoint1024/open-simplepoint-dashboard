package org.simplepoint.plugin.dna.runtime.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Never-deleted serialization boundary and monotonic ownership fence for a tenant resource. */
@Entity
@Table(name = "simpoint_dna_resource_projections", uniqueConstraints = @UniqueConstraint(
    name = "uk_dna_resource_projection", columnNames = {"tenant_id", "resource_code"}))
@Getter
@Setter
public class PhysicalResourceProjectionEntity {
  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "resource_code", length = 160, nullable = false, updatable = false)
  private String resourceCode;

  @Column(name = "owner_key", length = 160)
  private String ownerKey;

  @Column(nullable = false)
  private long generation;

  @Column(name = "formally_offline", nullable = false)
  private boolean formallyOffline;

  @Column(name = "physical_model_id", length = 36)
  private String physicalModelId;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  @Column(name = "created_by", length = 64, nullable = false, updatable = false)
  private String createdBy;

  @Column(name = "updated_by", length = 64, nullable = false)
  private String updatedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
