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
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.springframework.core.annotation.Order;

/**
 * A directed edge in the data lineage graph connecting a source node
 * to a target node with an optional transformation description.
 */
@Data
@Entity
@Table(name = "simpoint_dna_data_lineage_edges")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "数据血缘边", description = "数据血缘图谱中的有向边")
@Schema(title = "i18n:dna.dataLineage.edge.entity.title", description = "i18n:dna.dataLineage.edge.entity.description")
public class DataLineageEdge extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "i18n:dna.dataLineage.edge.title.sourceNodeId",
      description = "i18n:dna.dataLineage.edge.description.sourceNodeId",
      maxLength = 64
  )
  @Column(length = 64, nullable = false)
  private String sourceNodeId;

  @Transient
  @Schema(
      title = "i18n:dna.dataLineage.edge.title.sourceNodeName",
      description = "i18n:dna.dataLineage.edge.description.sourceNodeName",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private String sourceNodeName;

  @Order(1)
  @Schema(
      title = "i18n:dna.dataLineage.edge.title.targetNodeId",
      description = "i18n:dna.dataLineage.edge.description.targetNodeId",
      maxLength = 64
  )
  @Column(length = 64, nullable = false)
  private String targetNodeId;

  @Transient
  @Schema(
      title = "i18n:dna.dataLineage.edge.title.targetNodeName",
      description = "i18n:dna.dataLineage.edge.description.targetNodeName",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private String targetNodeName;

  @Order(2)
  @Schema(
      title = "i18n:dna.dataLineage.edge.title.edgeType",
      description = "i18n:dna.dataLineage.edge.description.edgeType",
      maxLength = 32,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 32, nullable = false)
  private String edgeType;

  @Order(3)
  @Schema(
      title = "i18n:dna.dataLineage.edge.title.transformDescription",
      description = "i18n:dna.dataLineage.edge.description.transformDescription",
      maxLength = 1024,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-textarea", value = "true")
          })
      }
  )
  @Column(length = 1024)
  private String transformDescription;

  @Order(4)
  @Schema(
      title = "i18n:dna.dataLineage.edge.title.description",
      description = "i18n:dna.dataLineage.edge.description.description",
      maxLength = 512
  )
  @Column(length = 512)
  private String description;
}
