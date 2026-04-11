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
 * Saved SQL query template for reuse in the SQL console.
 */
@Data
@Entity
@Table(name = "simpoint_dna_federation_query_templates")
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
        authority = "dna.federation.query-templates.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.federation.query-templates.edit"
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
        authority = "dna.federation.query-templates.delete"
    )
})
@Tag(name = "查询模板", description = "用于管理可复用的 SQL 查询模板")
@Schema(title = "i18n:dna.federation.queryTemplates.entity.title", description = "i18n:dna.federation.queryTemplates.entity.description")
public class FederationQueryTemplate extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "i18n:dna.federation.queryTemplates.title.name",
      description = "i18n:dna.federation.queryTemplates.description.name",
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

  @Order(1)
  @Schema(
      title = "i18n:dna.federation.queryTemplates.title.code",
      description = "i18n:dna.federation.queryTemplates.description.code",
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

  @Order(2)
  @Schema(title = "i18n:dna.federation.queryTemplates.title.catalogId", description = "i18n:dna.federation.queryTemplates.description.catalogId", maxLength = 64)
  @Column(length = 64)
  private String catalogId;

  @Transient
  @Schema(
      title = "i18n:dna.federation.queryTemplates.title.catalogName",
      description = "i18n:dna.federation.queryTemplates.description.catalogName",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private String catalogName;

  @Transient
  @Schema(title = "i18n:dna.federation.queryTemplates.title.catalogCode", description = "i18n:dna.federation.queryTemplates.description.catalogCode", accessMode = Schema.AccessMode.READ_ONLY)
  private String catalogCode;

  @Order(3)
  @Schema(
      title = "i18n:dna.federation.queryTemplates.title.sql",
      description = "i18n:dna.federation.queryTemplates.description.sql",
      maxLength = 12000,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "widget", value = "textarea")
          })
      }
  )
  @Column(name = "sql_text", length = 12000, nullable = false)
  private String sql;

  @Order(4)
  @Schema(
      title = "i18n:dna.federation.queryTemplates.title.tags",
      description = "i18n:dna.federation.queryTemplates.description.tags",
      maxLength = 512,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 512)
  private String tags;

  @Order(5)
  @Schema(
      title = "i18n:dna.federation.queryTemplates.title.isPublic",
      description = "i18n:dna.federation.queryTemplates.description.isPublic",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(name = "is_public")
  private Boolean isPublic;

  @Order(6)
  @Schema(
      title = "i18n:dna.federation.queryTemplates.title.enabled",
      description = "i18n:dna.federation.queryTemplates.description.enabled",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean enabled;

  @Order(7)
  @Schema(title = "i18n:dna.federation.queryTemplates.title.description", description = "i18n:dna.federation.queryTemplates.description.description", maxLength = 512)
  @Column(length = 512)
  private String description;
}
