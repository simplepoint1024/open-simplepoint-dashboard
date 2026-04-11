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
 * Data asset definition that groups physical database objects into logical assets.
 */
@Data
@Entity
@Table(name = "simpoint_dna_data_assets")
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
        authority = "dna.data-assets.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.data-assets.edit"
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
        authority = "dna.data-assets.delete"
    )
})
@Tag(name = "数据资产", description = "用于管理逻辑数据资产")
@Schema(title = "i18n:dna.dataAssets.entity.title", description = "i18n:dna.dataAssets.entity.description")
public class DataAsset extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "i18n:dna.dataAssets.title.name",
      description = "i18n:dna.dataAssets.description.name",
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
      title = "i18n:dna.dataAssets.title.code",
      description = "i18n:dna.dataAssets.description.code",
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
  @Schema(title = "i18n:dna.dataAssets.title.catalogId", description = "i18n:dna.dataAssets.description.catalogId", maxLength = 64)
  @Column(length = 64, nullable = false)
  private String catalogId;

  @Transient
  @Schema(
      title = "i18n:dna.dataAssets.title.catalogName",
      description = "i18n:dna.dataAssets.description.catalogName",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private String catalogName;

  @Transient
  @Schema(title = "i18n:dna.dataAssets.title.catalogCode", description = "i18n:dna.dataAssets.description.catalogCode", accessMode = Schema.AccessMode.READ_ONLY)
  private String catalogCode;

  @Order(3)
  @Schema(
      title = "i18n:dna.dataAssets.title.schemaPattern",
      description = "i18n:dna.dataAssets.description.schemaPattern",
      maxLength = 256,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 256)
  private String schemaPattern;

  @Order(4)
  @Schema(
      title = "i18n:dna.dataAssets.title.tablePattern",
      description = "i18n:dna.dataAssets.description.tablePattern",
      maxLength = 256,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 256)
  private String tablePattern;

  @Order(5)
  @Schema(
      title = "i18n:dna.dataAssets.title.assetType",
      description = "i18n:dna.dataAssets.description.assetType",
      maxLength = 32,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 32, nullable = false)
  private String assetType;

  @Order(6)
  @Schema(
      title = "i18n:dna.dataAssets.title.owner",
      description = "i18n:dna.dataAssets.description.owner",
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128)
  private String owner;

  @Order(7)
  @Schema(
      title = "i18n:dna.dataAssets.title.tags",
      description = "i18n:dna.dataAssets.description.tags",
      maxLength = 512,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 512)
  private String tags;

  @Order(8)
  @Schema(
      title = "i18n:dna.dataAssets.title.enabled",
      description = "i18n:dna.dataAssets.description.enabled",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean enabled;

  @Order(9)
  @Schema(title = "i18n:dna.dataAssets.title.description", description = "i18n:dna.dataAssets.description.description", maxLength = 512)
  @Column(length = 512)
  private String description;
}
