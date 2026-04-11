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
 * A node in the data lineage graph. Represents a physical table or view
 * within a datasource, tracking upstream and downstream relationships.
 */
@Data
@Entity
@Table(name = "simpoint_dna_data_lineage_nodes")
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
        authority = "dna.data-lineage.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.data-lineage.edit"
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
        authority = "dna.data-lineage.delete"
    )
})
@Tag(name = "数据血缘节点", description = "用于管理数据血缘图谱中的节点")
@Schema(title = "i18n:dna.dataLineage.node.entity.title", description = "i18n:dna.dataLineage.node.entity.description")
public class DataLineageNode extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "i18n:dna.dataLineage.node.title.name",
      description = "i18n:dna.dataLineage.node.description.name",
      maxLength = 256,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 256, nullable = false)
  private String name;

  @Order(1)
  @Schema(
      title = "i18n:dna.dataLineage.node.title.catalogId",
      description = "i18n:dna.dataLineage.node.description.catalogId",
      maxLength = 64
  )
  @Column(length = 64, nullable = false)
  private String catalogId;

  @Transient
  @Schema(
      title = "i18n:dna.dataLineage.node.title.catalogName",
      description = "i18n:dna.dataLineage.node.description.catalogName",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private String catalogName;

  @Order(2)
  @Schema(
      title = "i18n:dna.dataLineage.node.title.nodeType",
      description = "i18n:dna.dataLineage.node.description.nodeType",
      maxLength = 32,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 32, nullable = false)
  private String nodeType;

  @Order(3)
  @Schema(
      title = "i18n:dna.dataLineage.node.title.schemaName",
      description = "i18n:dna.dataLineage.node.description.schemaName",
      maxLength = 128,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128)
  private String schemaName;

  @Order(4)
  @Schema(
      title = "i18n:dna.dataLineage.node.title.tableName",
      description = "i18n:dna.dataLineage.node.description.tableName",
      maxLength = 256,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 256, nullable = false)
  private String tableName;

  @Order(5)
  @Schema(
      title = "i18n:dna.dataLineage.node.title.columnName",
      description = "i18n:dna.dataLineage.node.description.columnName",
      maxLength = 128
  )
  @Column(length = 128)
  private String columnName;

  @Order(6)
  @Schema(
      title = "i18n:dna.dataLineage.node.title.tags",
      description = "i18n:dna.dataLineage.node.description.tags",
      maxLength = 512,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 512)
  private String tags;

  @Order(7)
  @Schema(
      title = "i18n:dna.dataLineage.node.title.description",
      description = "i18n:dna.dataLineage.node.description.description",
      maxLength = 512
  )
  @Column(length = 512)
  private String description;
}
