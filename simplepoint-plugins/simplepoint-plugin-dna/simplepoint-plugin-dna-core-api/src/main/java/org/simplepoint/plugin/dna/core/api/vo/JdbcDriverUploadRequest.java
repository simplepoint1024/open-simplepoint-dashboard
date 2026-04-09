package org.simplepoint.plugin.dna.core.api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Request payload for creating a driver definition from an uploaded JDBC driver jar.
 */
@Data
@Schema(name = "JdbcDriverUploadRequest", description = "i18n:dna.drivers.uploadRequest.description")
public class JdbcDriverUploadRequest {

  @Schema(title = "i18n:dna.drivers.title.name", description = "i18n:dna.drivers.description.name", maxLength = 128)
  private String name;

  @Schema(title = "i18n:dna.drivers.title.code", description = "i18n:dna.drivers.description.code", maxLength = 128)
  private String code;

  @Schema(title = "i18n:dna.drivers.title.databaseType", description = "i18n:dna.drivers.description.databaseType", maxLength = 64)
  private String databaseType;

  @Schema(title = "i18n:dna.drivers.title.downloadUrl", description = "i18n:dna.drivers.description.downloadUrl", format = "uri", maxLength = 2048)
  private String downloadUrl;

  @Schema(title = "i18n:dna.drivers.title.enabled", description = "i18n:dna.drivers.description.enabled")
  private Boolean enabled;

  @Schema(title = "i18n:dna.drivers.title.description", description = "i18n:dna.drivers.description.description", maxLength = 512)
  private String description;
}
