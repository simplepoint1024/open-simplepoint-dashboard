package org.simplepoint.plugin.i18n.api.entity;

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

/**
 * Represents a country with various attributes.
 */
@Data
@Entity
@Table(name = "i18n_countries")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = "添加", key = "add", icon = "PlusCircleOutlined", sort = 0, argumentMaxSize = 0, argumentMinSize = 0
    ),
    @ButtonDeclaration(
        title = "编辑", key = "edit", color = "orange", icon = "EditOutlined", sort = 1,
        argumentMinSize = 1, argumentMaxSize = 1
    ),
    @ButtonDeclaration(
        title = "删除", key = "delete", color = "danger", icon = "MinusCircleOutlined", sort = 2,
        argumentMinSize = 1, argumentMaxSize = 10, danger = true
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "国家对象", description = "用于管理系统中的国家")
public class Countries extends BaseEntityImpl<String> {

  @Schema(description = "国家ISO代码", example = "US", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(length = 32, nullable = false, unique = true)
  private String isoCode;

  @Schema(description = "国家英文名称", example = "United States", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(length = 64, nullable = false, unique = true)
  private String nameEnglish;

  @Schema(description = "国家本地名称", example = "United States", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(length = 64, nullable = false, unique = true)
  private String nameLocal;

  @Schema(description = "货币代码", example = "USD", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(length = 8, nullable = false, unique = true)
  private String currencyCode;

  @Schema(description = "所属地区", example = "North America", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(length = 64, nullable = false, unique = true)
  private String region;

  @Schema(description = "是否活跃", example = "true", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(nullable = false, unique = true)
  private Boolean isActive;

}