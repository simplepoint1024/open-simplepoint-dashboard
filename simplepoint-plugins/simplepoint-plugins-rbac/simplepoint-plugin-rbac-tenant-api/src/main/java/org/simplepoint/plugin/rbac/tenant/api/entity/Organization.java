package org.simplepoint.plugin.rbac.tenant.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.springframework.core.annotation.Order;

/**
 * Represents a tenant-scoped organization entity.
 */
@Data
@Entity
@Table(
    name = "simpoint_saas_organizations",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_simpoint_saas_org_tenant_code", columnNames = {"tenant_id", "code"})
    }
)
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "organizations.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "organizations.edit"
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
        authority = "organizations.delete"
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "组织机构对象", description = "用于管理当前租户下的组织机构")
@Schema(title = "组织机构对象", description = "用于管理当前租户下的组织机构")
public class Organization extends TenantBaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "组织名称",
      description = "组织机构的展示名称",
      example = "总部",
      maxLength = 128,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 128, nullable = false)
  private String name;

  @Order(1)
  @Schema(
      title = "组织编码",
      description = "组织机构在租户内的唯一业务编码",
      example = "HQ",
      maxLength = 128,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 128, nullable = false)
  private String code;

  @Order(2)
  @Schema(
      title = "上级组织",
      description = "当前组织所属的上级组织",
      maxLength = 36,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
              @ExtensionProperty(name = "widget", value = "select"),
          })
      }
  )
  @Column(length = 36)
  private String parentId;

  @Order(3)
  @Schema(
      title = "描述",
      description = "组织机构的简要说明",
      example = "集团总部组织",
      maxLength = 512,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 512)
  private String description;

  @Order(4)
  @Schema(
      title = "排序",
      description = "数值越小越靠前展示",
      example = "0",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private Integer sort;

  @Order(5)
  @Schema(
      title = "启用",
      description = "当前组织是否启用",
      example = "true",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(nullable = false)
  private Boolean enabled;
}
