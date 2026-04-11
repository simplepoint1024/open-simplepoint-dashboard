package org.simplepoint.plugin.dna.federation.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
 * Federation query policy definition.
 */
@Data
@Entity
@Table(name = "simpoint_dna_federation_query_policies")
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
        authority = "dna.federation.query-policies.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.federation.query-policies.edit"
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
        authority = "dna.federation.query-policies.delete"
    )
})
@Tag(name = "查询策略", description = "用于管理联邦查询策略")
@Schema(title = "i18n:dna.federation.queryPolicies.entity.title", description = "i18n:dna.federation.queryPolicies.entity.description")
public class FederationQueryPolicy extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "i18n:dna.federation.queryPolicies.title.catalogId", description = "i18n:dna.federation.queryPolicies.description.catalogId", maxLength = 64)
  @Column(length = 64, nullable = false)
  private String catalogId;

  @Transient
  @Schema(
      title = "i18n:dna.federation.queryPolicies.title.catalogName",
      description = "i18n:dna.federation.queryPolicies.description.catalogName",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private String catalogName;

  @Transient
  @Schema(title = "i18n:dna.federation.queryPolicies.title.catalogCode", description = "i18n:dna.federation.queryPolicies.description.catalogCode", accessMode = Schema.AccessMode.READ_ONLY)
  private String catalogCode;

  @Order(1)
  @Schema(
      title = "i18n:dna.federation.queryPolicies.title.name",
      description = "i18n:dna.federation.queryPolicies.description.name",
      maxLength = 128,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128, nullable = false)
  private String name;

  @Order(2)
  @Schema(
      title = "i18n:dna.federation.queryPolicies.title.code",
      description = "i18n:dna.federation.queryPolicies.description.code",
      maxLength = 128,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128, nullable = false, unique = true)
  private String code;

  @Order(3)
  @Schema(
      title = "i18n:dna.federation.queryPolicies.title.allowSqlConsole",
      description = "i18n:dna.federation.queryPolicies.description.allowSqlConsole",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean allowSqlConsole;

  @Order(4)
  @Schema(
      title = "i18n:dna.federation.queryPolicies.title.allowCrossSourceJoin",
      description = "i18n:dna.federation.queryPolicies.description.allowCrossSourceJoin",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean allowCrossSourceJoin;

  @Order(5)
  @Schema(
      title = "i18n:dna.federation.queryPolicies.title.maxRows",
      description = "i18n:dna.federation.queryPolicies.description.maxRows",
      minimum = "1",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Integer maxRows;

  @Order(6)
  @Schema(
      title = "i18n:dna.federation.queryPolicies.title.timeoutMs",
      description = "i18n:dna.federation.queryPolicies.description.timeoutMs",
      minimum = "1",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Integer timeoutMs;

  @Order(7)
  @Schema(
      title = "i18n:dna.federation.queryPolicies.title.enabled",
      description = "i18n:dna.federation.queryPolicies.description.enabled",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean enabled;

  @Order(8)
  @Schema(title = "i18n:dna.federation.queryPolicies.title.description", description = "i18n:dna.federation.queryPolicies.description.description", maxLength = 512)
  @Column(length = 512)
  private String description;
}
