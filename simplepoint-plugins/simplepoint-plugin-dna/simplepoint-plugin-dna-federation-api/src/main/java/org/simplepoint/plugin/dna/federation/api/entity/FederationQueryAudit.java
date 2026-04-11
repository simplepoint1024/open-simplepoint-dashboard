package org.simplepoint.plugin.dna.federation.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.springframework.core.annotation.Order;

/**
 * Federation query audit record.
 */
@Data
@Entity
@Table(name = "simpoint_dna_federation_query_audits")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "查询审计", description = "用于记录联邦查询执行情况")
@Schema(title = "i18n:dna.federation.queryAudits.entity.title", description = "i18n:dna.federation.queryAudits.entity.description")
public class FederationQueryAudit extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "i18n:dna.federation.queryAudits.title.catalogCode",
      description = "i18n:dna.federation.queryAudits.description.catalogCode",
      maxLength = 128,
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128)
  private String catalogCode;

  @Order(1)
  @Schema(
      title = "i18n:dna.federation.queryAudits.title.viewCode",
      description = "i18n:dna.federation.queryAudits.description.viewCode",
      maxLength = 128,
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 128)
  private String viewCode;

  @Order(2)
  @Schema(
      title = "i18n:dna.federation.queryAudits.title.status",
      description = "i18n:dna.federation.queryAudits.description.status",
      maxLength = 32,
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  @Column(length = 32)
  private String status;

  @Order(3)
  @Schema(
      title = "i18n:dna.federation.queryAudits.title.executedAt",
      description = "i18n:dna.federation.queryAudits.description.executedAt",
      type = "string",
      format = "date-time",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Instant executedAt;

  @Order(4)
  @Schema(
      title = "i18n:dna.federation.queryAudits.title.executionTimeMs",
      description = "i18n:dna.federation.queryAudits.description.executionTimeMs",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Long executionTimeMs;

  @Order(5)
  @Schema(
      title = "i18n:dna.federation.queryAudits.title.resultRows",
      description = "i18n:dna.federation.queryAudits.description.resultRows",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true")
          })
      }
  )
  private Long resultRows;

  @Order(6)
  @Schema(
      title = "i18n:dna.federation.queryAudits.title.executedBy",
      description = "i18n:dna.federation.queryAudits.description.executedBy",
      maxLength = 128,
      accessMode = Schema.AccessMode.READ_ONLY
  )
  @Column(length = 128)
  private String executedBy;

  @Order(7)
  @Schema(
      title = "i18n:dna.federation.queryAudits.title.queryText",
      description = "i18n:dna.federation.queryAudits.description.queryText",
      maxLength = 12000,
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
              @ExtensionProperty(name = "widget", value = "textarea")
          })
      }
  )
  @Column(length = 12000)
  private String queryText;

  @Order(8)
  @Schema(
      title = "i18n:dna.federation.queryAudits.title.pushdownSummary",
      description = "i18n:dna.federation.queryAudits.description.pushdownSummary",
      maxLength = 4000,
      accessMode = Schema.AccessMode.READ_ONLY
  )
  @Column(length = 4000)
  private String pushdownSummary;

  @Order(9)
  @Schema(
      title = "i18n:dna.federation.queryAudits.title.errorMessage",
      description = "i18n:dna.federation.queryAudits.description.errorMessage",
      maxLength = 4000,
      accessMode = Schema.AccessMode.READ_ONLY
  )
  @Column(length = 4000)
  private String errorMessage;
}
