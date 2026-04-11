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
 * Federation schema definition.
 */
@Data
@Entity
@Table(name = "simpoint_dna_federation_schemas")
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
        authority = "dna.federation.schemas.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.federation.schemas.edit"
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
        authority = "dna.federation.schemas.delete"
    )
})
@Tag(name = "逻辑 Schema", description = "用于管理联邦目录下的逻辑 Schema")
@Schema(title = "i18n:dna.federation.schemas.entity.title", description = "i18n:dna.federation.schemas.entity.description")
public class FederationSchema extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "i18n:dna.federation.schemas.title.catalogId", description = "i18n:dna.federation.schemas.description.catalogId", maxLength = 64)
  @Column(length = 64, nullable = false)
  private String catalogId;

  @Transient
  @Schema(
      title = "i18n:dna.federation.schemas.title.catalogName",
      description = "i18n:dna.federation.schemas.description.catalogName",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private String catalogName;

  @Transient
  @Schema(title = "i18n:dna.federation.schemas.title.catalogCode", description = "i18n:dna.federation.schemas.description.catalogCode", accessMode = Schema.AccessMode.READ_ONLY)
  private String catalogCode;

  @Order(1)
  @Schema(
      title = "i18n:dna.federation.schemas.title.name",
      description = "i18n:dna.federation.schemas.description.name",
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
      title = "i18n:dna.federation.schemas.title.code",
      description = "i18n:dna.federation.schemas.description.code",
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
      title = "i18n:dna.federation.schemas.title.enabled",
      description = "i18n:dna.federation.schemas.description.enabled",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean enabled;

  @Order(4)
  @Schema(title = "i18n:dna.federation.schemas.title.description", description = "i18n:dna.federation.schemas.description.description", maxLength = 512)
  @Column(length = 512)
  private String description;
}
