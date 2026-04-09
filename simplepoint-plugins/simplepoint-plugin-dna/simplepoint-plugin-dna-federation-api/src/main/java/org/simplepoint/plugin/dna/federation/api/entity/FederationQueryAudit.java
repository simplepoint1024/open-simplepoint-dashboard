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
@Schema(title = "查询审计", description = "联邦查询执行摘要与结果规模审计记录")
public class FederationQueryAudit extends BaseEntityImpl<String> {

  @Order(0)
  @Schema(
      title = "目录编码",
      description = "本次查询命中的联邦目录编码",
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
      title = "视图编码",
      description = "本次查询命中的逻辑视图编码",
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
      title = "执行状态",
      description = "本次查询执行结果状态",
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
      title = "执行时间",
      description = "查询实际执行时间",
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
      title = "执行耗时(ms)",
      description = "查询执行耗时毫秒数",
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
      title = "结果行数",
      description = "查询返回结果的总行数",
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
      title = "执行人",
      description = "提交本次查询的用户标识",
      maxLength = 128,
      accessMode = Schema.AccessMode.READ_ONLY
  )
  @Column(length = 128)
  private String executedBy;

  @Order(7)
  @Schema(
      title = "查询 SQL",
      description = "本次联邦查询执行的 SQL 文本",
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
      title = "下推摘要",
      description = "哪些谓词、投影或分页被下推到源库的摘要信息",
      maxLength = 4000,
      accessMode = Schema.AccessMode.READ_ONLY
  )
  @Column(length = 4000)
  private String pushdownSummary;

  @Order(9)
  @Schema(
      title = "错误信息",
      description = "执行失败时记录的错误摘要",
      maxLength = 4000,
      accessMode = Schema.AccessMode.READ_ONLY
  )
  @Column(length = 4000)
  private String errorMessage;
}
