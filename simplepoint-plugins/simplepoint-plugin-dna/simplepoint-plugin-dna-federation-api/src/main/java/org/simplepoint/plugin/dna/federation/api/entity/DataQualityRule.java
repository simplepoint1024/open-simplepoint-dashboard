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
 * Data quality rule definition. Each rule specifies a check to be run against
 * a datasource table/column to validate data integrity.
 */
@Data
@Entity
@Table(name = "simpoint_dna_data_quality_rules")
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
        authority = "dna.data-quality.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "dna.data-quality.edit"
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
        authority = "dna.data-quality.delete"
    )
})
@Tag(name = "数据质量规则", description = "用于管理数据质量检查规则")
@Schema(title = "i18n:dna.dataQuality.entity.title", description = "i18n:dna.dataQuality.entity.description")
public class DataQualityRule extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "i18n:dna.dataQuality.title.name",
      description = "i18n:dna.dataQuality.description.name",
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
      title = "i18n:dna.dataQuality.title.code",
      description = "i18n:dna.dataQuality.description.code",
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
  @Schema(title = "i18n:dna.dataQuality.title.catalogId", description = "i18n:dna.dataQuality.description.catalogId", maxLength = 64)
  @Column(length = 64, nullable = false)
  private String catalogId;

  @Transient
  @Schema(
      title = "i18n:dna.dataQuality.title.catalogName",
      description = "i18n:dna.dataQuality.description.catalogName",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private String catalogName;

  @Order(3)
  @Schema(
      title = "i18n:dna.dataQuality.title.ruleType",
      description = "i18n:dna.dataQuality.description.ruleType",
      maxLength = 32,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 32, nullable = false)
  private String ruleType;

  @Order(4)
  @Schema(
      title = "i18n:dna.dataQuality.title.targetTable",
      description = "i18n:dna.dataQuality.description.targetTable",
      maxLength = 256,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 256, nullable = false)
  private String targetTable;

  @Order(5)
  @Schema(
      title = "i18n:dna.dataQuality.title.targetColumn",
      description = "i18n:dna.dataQuality.description.targetColumn",
      maxLength = 128
  )
  @Column(length = 128)
  private String targetColumn;

  @Order(6)
  @Schema(
      title = "i18n:dna.dataQuality.title.checkSql",
      description = "i18n:dna.dataQuality.description.checkSql",
      maxLength = 4096,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-textarea", value = "true")
          })
      }
  )
  @Column(name = "check_sql", length = 4096)
  private String checkSql;

  @Order(7)
  @Schema(
      title = "i18n:dna.dataQuality.title.expectedValue",
      description = "i18n:dna.dataQuality.description.expectedValue",
      maxLength = 256
  )
  @Column(length = 256)
  private String expectedValue;

  @Order(8)
  @Schema(
      title = "i18n:dna.dataQuality.title.severity",
      description = "i18n:dna.dataQuality.description.severity",
      maxLength = 16,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 16, nullable = false)
  private String severity;

  @Order(9)
  @Schema(
      title = "i18n:dna.dataQuality.title.lastRunStatus",
      description = "i18n:dna.dataQuality.description.lastRunStatus",
      maxLength = 16,
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 16)
  private String lastRunStatus;

  @Order(10)
  @Schema(
      title = "i18n:dna.dataQuality.title.lastRunMessage",
      description = "i18n:dna.dataQuality.description.lastRunMessage",
      maxLength = 2048,
      accessMode = Schema.AccessMode.READ_ONLY
  )
  @Column(length = 2048)
  private String lastRunMessage;

  @Order(11)
  @Schema(
      title = "i18n:dna.dataQuality.title.lastRunAt",
      description = "i18n:dna.dataQuality.description.lastRunAt",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column
  private java.time.Instant lastRunAt;

  @Order(12)
  @Schema(
      title = "i18n:dna.dataQuality.title.enabled",
      description = "i18n:dna.dataQuality.description.enabled",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column
  private Boolean enabled;

  @Order(13)
  @Schema(
      title = "i18n:dna.dataQuality.title.description",
      description = "i18n:dna.dataQuality.description.description",
      maxLength = 512
  )
  @Column(length = 512)
  private String description;
}
