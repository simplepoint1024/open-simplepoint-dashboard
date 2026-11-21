package org.simplepoint.plugin.dna.runtime.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-tenant control-plane barrier; revision is a catalog revision, never a resource generation.
 */
@Entity
@Getter
@Setter
@Table(name = "simpoint_dna_relation_catalogs")
public class PhysicalRelationCatalogEntity {
  @Id
  @Column(name = "tenant_id", length = 255)
  private String tenantId;

  @Column(nullable = false)
  private long revision;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
