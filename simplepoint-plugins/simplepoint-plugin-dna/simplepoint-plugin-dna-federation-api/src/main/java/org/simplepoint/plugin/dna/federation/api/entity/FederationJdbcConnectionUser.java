package org.simplepoint.plugin.dna.federation.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Set;
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
 * Dedicated JDBC access grant bound to a system user and one federation catalog.
 */
@Data
@Entity
@Table(name = "simpoint_dna_federation_jdbc_users")
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
        authority = "dna.federation.jdbc-users.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.federation.jdbc-users.edit"
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
        authority = "dna.federation.jdbc-users.delete"
    )
})
@Tag(name = "JDBC连接用户", description = "用于为系统用户配置 DNA JDBC 驱动访问授权")
@Schema(title = "i18n:dna.federation.jdbcUsers.entity.title", description = "i18n:dna.federation.jdbcUsers.entity.description")
public class FederationJdbcConnectionUser extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "i18n:dna.federation.jdbcUsers.title.catalogId",
      description = "i18n:dna.federation.jdbcUsers.description.catalogId",
      maxLength = 64,
      minLength = 1
  )
  @Column(length = 64, nullable = false)
  private String catalogId;

  @Order(1)
  @Schema(
      title = "i18n:dna.federation.jdbcUsers.title.catalogName",
      description = "i18n:dna.federation.jdbcUsers.description.catalogName",
      accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128, nullable = false)
  private String catalogName;

  @Order(2)
  @Schema(
      title = "i18n:dna.federation.jdbcUsers.title.catalogCode",
      description = "i18n:dna.federation.jdbcUsers.description.catalogCode",
      accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128, nullable = false)
  private String catalogCode;

  @Order(3)
  @Schema(
      title = "i18n:dna.federation.jdbcUsers.title.userId",
      description = "i18n:dna.federation.jdbcUsers.description.userId",
      maxLength = 64,
      minLength = 1
  )
  @Column(length = 64, nullable = false)
  private String userId;

  @Order(4)
  @Schema(
      title = "i18n:dna.federation.jdbcUsers.title.userDisplayName",
      description = "i18n:dna.federation.jdbcUsers.description.userDisplayName",
      accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128, nullable = false)
  private String userDisplayName;

  @Order(5)
  @Schema(
      title = "i18n:dna.federation.jdbcUsers.title.userEmail",
      description = "i18n:dna.federation.jdbcUsers.description.userEmail",
      accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 64,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 64)
  private String userEmail;

  @Order(6)
  @Schema(
      title = "i18n:dna.federation.jdbcUsers.title.userPhoneNumber",
      description = "i18n:dna.federation.jdbcUsers.description.userPhoneNumber",
      accessMode = Schema.AccessMode.READ_ONLY,
      maxLength = 32
  )
  @Column(length = 32)
  private String userPhoneNumber;

  @Order(7)
  @Schema(
      title = "i18n:dna.federation.jdbcUsers.title.operationPermissions",
      description = "i18n:dna.federation.jdbcUsers.description.operationPermissions",
      allowableValues = {"METADATA", "QUERY", "EXPLAIN", "DDL", "DML"},
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 1024, nullable = false)
  private Set<String> operationPermissions;

  @Order(8)
  @Schema(
      title = "i18n:dna.federation.jdbcUsers.title.enabled",
      description = "i18n:dna.federation.jdbcUsers.description.enabled",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean enabled;

  @Order(9)
  @Schema(
      title = "i18n:dna.federation.jdbcUsers.title.description",
      description = "i18n:dna.federation.jdbcUsers.description.description",
      maxLength = 512
  )
  @Column(length = 512)
  private String description;
}
