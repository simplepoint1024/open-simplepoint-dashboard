package org.simplepoint.plugin.ai.catalog.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import org.simplepoint.plugin.ai.catalog.api.model.CatalogEntryStatus;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogPackageKind;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;

/**
 * Locally cached external extension metadata.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_catalog_entries",
    indexes = {
        @Index(name = "idx_simpoint_ai_catalog_source", columnList = "source, status"),
        @Index(name = "idx_simpoint_ai_catalog_name", columnList = "registry_name")
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "AI extension catalog entry")
public class AiCatalogEntry extends BaseEntityImpl<String> {

  @Column(name = "external_key", length = 64, nullable = false, unique = true)
  private String externalKey;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private CatalogSource source;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private CatalogPackageKind kind;

  @Column(name = "registry_name", length = 512, nullable = false)
  private String registryName;

  @Column(length = 128, nullable = false)
  private String version;

  @Column(length = 256)
  private String title;

  @Column(length = 2048)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private CatalogEntryStatus status;

  @Column(name = "transport_type", length = 32)
  private String transportType;

  @Column(name = "endpoint_url", length = 2048)
  private String endpointUrl;

  @Column(name = "repository_url", length = 2048)
  private String repositoryUrl;

  @Column(name = "website_url", length = 2048)
  private String websiteUrl;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "registry_updated_at")
  private Instant registryUpdatedAt;

  @JsonIgnore
  @Column(name = "raw_json", columnDefinition = "TEXT", nullable = false)
  private String rawJson;

  @Version
  @Column(name = "lock_version", nullable = false)
  @Schema(hidden = true)
  private Long lockVersion;
}
