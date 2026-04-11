package org.simplepoint.plugin.dna.federation.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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
 * Federation catalog definition.
 */
@Data
@Entity
@Table(name = "simpoint_dna_federation_catalogs")
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
        authority = "dna.federation.catalogs.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.federation.catalogs.edit"
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
        authority = "dna.federation.catalogs.delete"
    )
})
@Tag(name = "数据目录", description = "用于管理 DNA 联邦查询目录")
@Schema(title = "i18n:dna.federation.catalogs.entity.title", description = "i18n:dna.federation.catalogs.entity.description")
public class FederationCatalog extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "i18n:dna.federation.catalogs.title.name",
      description = "i18n:dna.federation.catalogs.description.name",
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
      title = "i18n:dna.federation.catalogs.title.code",
      description = "i18n:dna.federation.catalogs.description.code",
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
  @Schema(
      title = "i18n:dna.federation.catalogs.title.catalogType",
      description = "i18n:dna.federation.catalogs.description.catalogType",
      allowableValues = {"VIRTUAL", "DATA_SOURCE"},
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 32)
  private String catalogType;

  @Order(3)
  @Schema(
      title = "i18n:dna.federation.catalogs.title.enabled",
      description = "i18n:dna.federation.catalogs.description.enabled",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean enabled;

  @Order(4)
  @Schema(title = "i18n:dna.federation.catalogs.title.description", description = "i18n:dna.federation.catalogs.description.description", maxLength = 512)
  @Column(length = 512)
  private String description;
}
