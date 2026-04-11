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
 * Federation logical view definition.
 */
@Data
@Entity
@Table(name = "simpoint_dna_federation_views")
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
        authority = "dna.federation.views.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.federation.views.edit"
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
        authority = "dna.federation.views.delete"
    )
})
@Tag(name = "逻辑视图", description = "用于管理联邦查询逻辑视图")
@Schema(title = "i18n:dna.federation.views.entity.title", description = "i18n:dna.federation.views.entity.description")
public class FederationView extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "i18n:dna.federation.views.title.schemaId", description = "i18n:dna.federation.views.description.schemaId", maxLength = 64)
  @Column(length = 64, nullable = false)
  private String schemaId;

  @Transient
  @Schema(
      title = "i18n:dna.federation.views.title.schemaName",
      description = "i18n:dna.federation.views.description.schemaName",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private String schemaName;

  @Transient
  @Schema(title = "i18n:dna.federation.views.title.schemaCode", description = "i18n:dna.federation.views.description.schemaCode", accessMode = Schema.AccessMode.READ_ONLY)
  private String schemaCode;

  @Order(1)
  @Schema(
      title = "i18n:dna.federation.views.title.name",
      description = "i18n:dna.federation.views.description.name",
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
      title = "i18n:dna.federation.views.title.code",
      description = "i18n:dna.federation.views.description.code",
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
      title = "i18n:dna.federation.views.title.definitionSql",
      description = "i18n:dna.federation.views.description.definitionSql",
      maxLength = 12000,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "widget", value = "textarea")
          })
      }
  )
  @Column(length = 12000, nullable = false)
  private String definitionSql;

  @Order(4)
  @Schema(
      title = "i18n:dna.federation.views.title.enabled",
      description = "i18n:dna.federation.views.description.enabled",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean enabled;

  @Order(5)
  @Schema(title = "i18n:dna.federation.views.title.description", description = "i18n:dna.federation.views.description.description", maxLength = 512)
  @Column(length = 512)
  private String description;
}
