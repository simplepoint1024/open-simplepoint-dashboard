package org.simplepoint.plugin.ai.catalog.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncMode;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncStatus;

/**
 * Durable cursor and health state for one external catalog source.
 */
@Data
@Entity
@Table(name = "simpoint_ai_catalog_sync_states")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI extension catalog synchronization state")
public class AiCatalogSyncState extends BaseEntityImpl<String> {

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false, unique = true)
  private CatalogSource source;

  @Enumerated(EnumType.STRING)
  @Column(name = "sync_mode", length = 16, nullable = false)
  private CatalogSyncMode syncMode;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private CatalogSyncStatus status;

  @Column(length = 2048)
  private String cursor;

  @Column(name = "sync_since")
  private Instant syncSince;

  @Column(name = "last_started_at")
  private Instant lastStartedAt;

  @Column(name = "last_completed_at")
  private Instant lastCompletedAt;

  @Column(name = "last_error", length = 2048)
  private String lastError;

  @Column(name = "synced_count", nullable = false)
  private Long syncedCount;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
