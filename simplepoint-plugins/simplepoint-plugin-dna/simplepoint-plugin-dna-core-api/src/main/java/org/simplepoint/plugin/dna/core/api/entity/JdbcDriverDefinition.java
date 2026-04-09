package org.simplepoint.plugin.dna.core.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
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
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.springframework.core.annotation.Order;

/**
 * JDBC driver definition managed by the DNA plugin.
 */
@Data
@Entity
@Table(name = "simpoint_dna_jdbc_drivers")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMinSize = 0,
        argumentMaxSize = 1,
        authority = "dna.drivers.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.drivers.edit"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.DELETE_TITLE,
        key = PublicButtonKeys.DELETE_KEY,
        color = "danger",
        icon = Icons.MINUS_CIRCLE,
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 10,
        danger = true,
        authority = "dna.drivers.delete"
    ),
    @ButtonDeclaration(
        title = "i18n:dna.drivers.button.download",
        key = "download",
        color = "blue",
        icon = Icons.SAFETY_OUTLINED,
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.drivers.download"
    ),
    @ButtonDeclaration(
        title = "i18n:dna.drivers.button.upload",
        key = "upload",
        color = "blue",
        icon = "CloudUploadOutlined",
        sort = 4,
        argumentMinSize = 0,
        argumentMaxSize = 1,
        authority = "dna.drivers.upload"
    )
})
@Tag(name = "JDBC驱动定义", description = "用于管理数据库驱动下载地址、本地上传驱动包以及 JDBC URL 校验规则")
@Schema(title = "i18n:dna.drivers.entity.title", description = "i18n:dna.drivers.entity.description")
public class JdbcDriverDefinition extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "i18n:dna.drivers.title.name", description = "i18n:dna.drivers.description.name", maxLength = 128, minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 128, nullable = false)
  private String name;

  @Order(1)
  @Schema(title = "i18n:dna.drivers.title.code", description = "i18n:dna.drivers.description.code", maxLength = 128, minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 128, nullable = false, unique = true)
  private String code;

  @Order(2)
  @Schema(title = "i18n:dna.drivers.title.databaseType", description = "i18n:dna.drivers.description.databaseType", maxLength = 64,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 64, nullable = false)
  private String databaseType;

  @Order(3)
  @Schema(title = "i18n:dna.drivers.title.version", description = "i18n:dna.drivers.description.version", maxLength = 64,
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 64)
  private String version;

  @Order(4)
  @Schema(title = "i18n:dna.drivers.title.driverClassName", description = "i18n:dna.drivers.description.driverClassName", maxLength = 256,
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 256, nullable = false)
  private String driverClassName;

  @Order(5)
  @Schema(title = "i18n:dna.drivers.title.downloadUrl", description = "i18n:dna.drivers.description.downloadUrl", format = "uri",
      maxLength = 2048,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 2048)
  private String downloadUrl;

  @Order(6)
  @Schema(title = "i18n:dna.drivers.title.jdbcUrlPattern", description = "i18n:dna.drivers.description.jdbcUrlPattern", maxLength = 1024,
      accessMode = Schema.AccessMode.READ_ONLY)
  @Column(length = 1024, nullable = false)
  private String jdbcUrlPattern;

  @Order(7)
  @Schema(title = "i18n:dna.drivers.title.enabled", description = "i18n:dna.drivers.description.enabled",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  private Boolean enabled;

  @Order(8)
  @Schema(title = "i18n:dna.drivers.title.description", description = "i18n:dna.drivers.description.description", maxLength = 512)
  @Column(length = 512)
  private String description;

  @Order(9)
  @Schema(title = "i18n:dna.drivers.title.localJarPath", description = "i18n:dna.drivers.description.localJarPath",
      accessMode = Schema.AccessMode.READ_ONLY, maxLength = 2048)
  @Column(length = 2048)
  private String localJarPath;

  @Order(10)
  @Schema(title = "i18n:dna.drivers.title.downloadedAt", description = "i18n:dna.drivers.description.downloadedAt", type = "string", format = "date-time",
      accessMode = Schema.AccessMode.READ_ONLY)
  private Instant downloadedAt;

  @Order(11)
  @Schema(title = "i18n:dna.drivers.title.lastDownloadMessage", description = "i18n:dna.drivers.description.lastDownloadMessage", accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 1024)
  @Column(length = 1024)
  private String lastDownloadMessage;
}
