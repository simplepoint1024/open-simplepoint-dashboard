package org.simplepoint.plugin.ai.knowledge.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeRetrievalMode;
import org.springframework.core.annotation.Order;

/**
 * Tenant-isolated knowledge base configuration.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_knowledge_bases",
    indexes = @Index(
        name = "idx_simpoint_ai_kb_scope",
        columnList = "scope_type, tenant_id"
    ),
    uniqueConstraints = @UniqueConstraint(
        name = "uk_simpoint_ai_kb_scope_code",
        columnNames = {"scope_type", "tenant_id", "code"}
    )
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMinSize = 0,
        argumentMaxSize = 1,
        authority = "ai.knowledge-bases.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.knowledge-bases.edit"
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
        authority = "ai.knowledge-bases.delete"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.knowledge-bases.button.documents",
        key = "documents",
        color = "blue",
        icon = "FileTextOutlined",
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.knowledge-bases.documents"
    ),
    @ButtonDeclaration(
        title = "i18n:ai.knowledge-bases.button.retrieve",
        key = "retrieve",
        color = "blue",
        icon = "SearchOutlined",
        sort = 4,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "ai.knowledge-bases.retrieve"
    )
})
@Schema(title = "i18n:ai.knowledge-bases.entity.title")
public class AiKnowledgeBase extends BaseEntityImpl<String> {

  @Order(0)
  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  @Schema(title = "i18n:ai.knowledge-bases.title.scopeType",
      accessMode = Schema.AccessMode.READ_ONLY)
  private AiResourceScope scopeType;

  @Order(1)
  @Column(name = "tenant_id", length = 64)
  @Schema(title = "i18n:ai.knowledge-bases.title.tenantId",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String tenantId;

  @Order(2)
  @Column(length = 128, nullable = false)
  @Schema(title = "i18n:ai.knowledge-bases.title.name", minLength = 1, maxLength = 128,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String name;

  @Order(3)
  @Column(length = 128, nullable = false)
  @Schema(title = "i18n:ai.knowledge-bases.title.code", minLength = 1, maxLength = 128,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String code;

  @Order(4)
  @Column(name = "embedding_model_id", length = 64)
  @Schema(title = "i18n:ai.knowledge-bases.title.embeddingModelId")
  private String embeddingModelId;

  @Order(5)
  @Column(name = "embedding_dimensions")
  @Schema(title = "i18n:ai.knowledge-bases.title.embeddingDimensions", minimum = "1",
      maximum = "2000")
  private Integer embeddingDimensions;

  @Order(6)
  @Column(name = "chunk_size", nullable = false)
  @Schema(title = "i18n:ai.knowledge-bases.title.chunkSize", minimum = "100",
      maximum = "8000")
  private Integer chunkSize;

  @Order(7)
  @Column(name = "chunk_overlap", nullable = false)
  @Schema(title = "i18n:ai.knowledge-bases.title.chunkOverlap", minimum = "0",
      maximum = "2000")
  private Integer chunkOverlap;

  @Order(8)
  @Enumerated(EnumType.STRING)
  @Column(name = "retrieval_mode", length = 16, nullable = false)
  @Schema(title = "i18n:ai.knowledge-bases.title.retrievalMode",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private AiKnowledgeRetrievalMode retrievalMode;

  @Order(9)
  @Column(name = "top_k", nullable = false)
  @Schema(title = "i18n:ai.knowledge-bases.title.topK", minimum = "1", maximum = "100")
  private Integer topK;

  @Order(10)
  @Column(name = "score_threshold", nullable = false)
  @Schema(title = "i18n:ai.knowledge-bases.title.scoreThreshold", minimum = "0",
      maximum = "1")
  private Double scoreThreshold;

  @Order(11)
  @Column(name = "vector_weight", nullable = false)
  @Schema(title = "i18n:ai.knowledge-bases.title.vectorWeight", minimum = "0",
      maximum = "1")
  private Double vectorWeight;

  @Order(12)
  @Column(name = "keyword_weight", nullable = false)
  @Schema(title = "i18n:ai.knowledge-bases.title.keywordWeight", minimum = "0",
      maximum = "1")
  private Double keywordWeight;

  @Order(13)
  @Schema(title = "i18n:ai.knowledge-bases.title.enabled",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Boolean enabled;

  @Order(14)
  @Column(length = 1024)
  @Schema(title = "i18n:ai.knowledge-bases.title.description", maxLength = 1024)
  private String description;

  @Transient
  @Schema(title = "i18n:ai.knowledge-bases.title.documentCount",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Long documentCount;

  @Transient
  @Schema(title = "i18n:ai.knowledge-bases.title.chunkCount",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Long chunkCount;
}
