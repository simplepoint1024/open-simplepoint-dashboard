package org.simplepoint.plugin.dna.core.api.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
 * Managed JDBC datasource definition.
 */
@Data
@Entity
@Table(name = "simpoint_dna_jdbc_data_sources")
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
        authority = "dna.data-sources.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.data-sources.edit"
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
        authority = "dna.data-sources.delete"
    ),
    @ButtonDeclaration(
        title = "i18n:dna.dataSources.button.connect",
        key = "connect",
        color = "blue",
        icon = Icons.SAFETY_OUTLINED,
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.data-sources.connect"
    )
})
@Tag(name = "JDBC数据源定义", description = "用于管理 JDBC 数据源连接配置和运行时连接状态")
@Schema(title = "i18n:dna.dataSources.entity.title", description = "i18n:dna.dataSources.entity.description")
public class JdbcDataSourceDefinition extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "i18n:dna.dataSources.title.name", description = "i18n:dna.dataSources.description.name", maxLength = 128, minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 128, nullable = false)
  private String name;

  @Order(1)
  @Schema(title = "i18n:dna.dataSources.title.code", description = "i18n:dna.dataSources.description.code", maxLength = 128, minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 128, nullable = false, unique = true)
  private String code;

  @Order(2)
  @Schema(title = "i18n:dna.dataSources.title.driverId", description = "i18n:dna.dataSources.description.driverId", maxLength = 64,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 64, nullable = false)
  private String driverId;

  @Transient
  @Schema(title = "i18n:dna.dataSources.title.driverCode", description = "i18n:dna.dataSources.description.driverCode", accessMode = Schema.AccessMode.READ_ONLY)
  private String driverCode;

  @Transient
  @Schema(title = "i18n:dna.dataSources.title.driverName", description = "i18n:dna.dataSources.description.driverName", accessMode = Schema.AccessMode.READ_ONLY)
  private String driverName;

  @Order(3)
  @Schema(title = "i18n:dna.dataSources.title.jdbcUrl", description = "i18n:dna.dataSources.description.jdbcUrl",
      maxLength = 2048,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 2048, nullable = false)
  private String jdbcUrl;

  @Order(4)
  @Schema(title = "i18n:dna.dataSources.title.username", description = "i18n:dna.dataSources.description.username", maxLength = 256,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 256)
  private String username;

  @Order(5)
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @Schema(title = "i18n:dna.dataSources.title.password", description = "i18n:dna.dataSources.description.password", accessMode = Schema.AccessMode.WRITE_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "widget", value = "password")
          })
      })
  @Column(length = 512)
  private String password;

  @Order(6)
  @Schema(title = "i18n:dna.dataSources.title.connectionProperties", description = "i18n:dna.dataSources.description.connectionProperties", maxLength = 4000)
  @Column(length = 4000)
  private String connectionProperties;

  @Order(7)
  @Schema(title = "i18n:dna.dataSources.title.enabled", description = "i18n:dna.dataSources.description.enabled",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  private Boolean enabled;

  @Order(8)
  @Schema(title = "i18n:dna.dataSources.title.description", description = "i18n:dna.dataSources.description.description", maxLength = 512)
  @Column(length = 512)
  private String description;

  @Order(9)
  @Schema(title = "i18n:dna.dataSources.title.lastConnectStatus", description = "i18n:dna.dataSources.description.lastConnectStatus", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 32)
  private String lastConnectStatus;

  @Order(10)
  @Schema(title = "i18n:dna.dataSources.title.lastConnectMessage", description = "i18n:dna.dataSources.description.lastConnectMessage", accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 1024)
  @Column(length = 1024)
  private String lastConnectMessage;

  @Order(11)
  @Schema(title = "i18n:dna.dataSources.title.lastTestedAt", description = "i18n:dna.dataSources.description.lastTestedAt", type = "string", format = "date-time",
      accessMode = Schema.AccessMode.READ_ONLY)
  private Instant lastTestedAt;

  @Order(12)
  @Schema(title = "i18n:dna.dataSources.title.databaseProductName", description = "i18n:dna.dataSources.description.databaseProductName", accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      })
  @Column(length = 128)
  private String databaseProductName;

  @Order(13)
  @Schema(title = "i18n:dna.dataSources.title.databaseProductVersion", description = "i18n:dna.dataSources.description.databaseProductVersion", accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 256)
  @Column(length = 256)
  private String databaseProductVersion;
}
