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
@Schema(title = "查询策略", description = "用于限制联邦 SQL 控制台和查询执行边界的策略定义")
public class FederationQueryPolicy extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "联邦目录", description = "所属联邦目录 ID", maxLength = 64)
  @Column(length = 64, nullable = false)
  private String catalogId;

  @Transient
  @Schema(
      title = "目录名称",
      description = "所属联邦目录名称",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private String catalogName;

  @Transient
  @Schema(title = "目录编码", description = "所属联邦目录编码", accessMode = Schema.AccessMode.READ_ONLY)
  private String catalogCode;

  @Order(1)
  @Schema(
      title = "策略名称",
      description = "用于展示的查询策略名称",
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
      title = "策略编码",
      description = "查询策略唯一业务编码",
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
      title = "允许 SQL 控制台",
      description = "是否允许该目录直接执行自由 SQL",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean allowSqlConsole;

  @Order(4)
  @Schema(
      title = "允许跨源 Join",
      description = "是否允许跨不同物理数据源执行联邦 Join",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean allowCrossSourceJoin;

  @Order(5)
  @Schema(
      title = "最大返回行数",
      description = "单次查询允许返回的最大结果行数",
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
      title = "超时毫秒数",
      description = "单次查询允许执行的最大时长",
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
      title = "是否启用",
      description = "是否允许该策略参与联邦查询治理",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Boolean enabled;

  @Order(8)
  @Schema(title = "描述", description = "查询策略备注说明", maxLength = 512)
  @Column(length = 512)
  private String description;
}
