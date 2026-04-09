package org.simplepoint.plugin.dna.core.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.springframework.core.annotation.Order;

/**
 * External JDBC dialect source definition managed by the DNA plugin.
 */
@Data
@Entity
@Table(name = "simpoint_dna_jdbc_dialect_sources")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "JDBC方言源定义", description = "用于管理 URL / 上传方式加载的 JDBC 方言源")
@Schema(title = "i18n:dna.dialects.entity.title", description = "i18n:dna.dialects.entity.description")
public class JdbcDialectSourceDefinition extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "i18n:dna.dialects.title.name", description = "i18n:dna.dialects.description.name", maxLength = 128)
  @Column(length = 128, nullable = false)
  private String name;

  @Order(1)
  @Schema(title = "i18n:dna.dialects.title.sourceType", description = "i18n:dna.dialects.description.sourceType", maxLength = 32)
  @Column(length = 32, nullable = false)
  private String sourceType;

  @Order(2)
  @Schema(title = "i18n:dna.dialects.title.sourceUrl", description = "i18n:dna.dialects.description.sourceUrl", format = "uri", maxLength = 2048)
  @Column(length = 2048)
  private String sourceUrl;

  @Order(3)
  @Schema(title = "i18n:dna.dialects.title.localJarPath", description = "i18n:dna.dialects.description.localJarPath", accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 2048)
  @Column(length = 2048)
  private String localJarPath;

  @Order(4)
  @Schema(title = "i18n:dna.dialects.title.enabled", description = "i18n:dna.dialects.description.enabled")
  private Boolean enabled;

  @Order(5)
  @Schema(title = "i18n:dna.dialects.title.description", description = "i18n:dna.dialects.description.description", maxLength = 512)
  @Column(length = 512)
  private String description;

  @Order(6)
  @Schema(title = "i18n:dna.dialects.title.loadedAt", description = "i18n:dna.dialects.description.loadedAt", accessMode = Schema.AccessMode.READ_ONLY,
      type = "string", format = "date-time")
  private Instant loadedAt;

  @Order(7)
  @Schema(title = "i18n:dna.dialects.title.lastLoadMessage", description = "i18n:dna.dialects.description.lastLoadMessage", accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 1024)
  @Column(length = 1024)
  private String lastLoadMessage;
}
