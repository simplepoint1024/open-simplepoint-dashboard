package org.simplepoint.plugin.ai.knowledge.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeDocumentSourceType;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeDocumentStatus;
import org.springframework.core.annotation.Order;

/**
 * Parsed source document stored in a knowledge base.
 */
@Data
@Entity
@Table(
    name = "simpoint_ai_knowledge_documents",
    indexes = {
        @Index(name = "idx_simpoint_ai_kb_doc_base", columnList = "knowledge_base_id"),
        @Index(name = "idx_simpoint_ai_kb_doc_scope", columnList = "scope_type, tenant_id"),
        @Index(name = "idx_simpoint_ai_kb_doc_storage", columnList = "storage_object_id")
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(title = "i18n:ai.knowledge-documents.entity.title")
public class AiKnowledgeDocument extends BaseEntityImpl<String> {

  @Order(0)
  @Column(name = "knowledge_base_id", length = 64, nullable = false)
  @Schema(title = "i18n:ai.knowledge-documents.title.knowledgeBaseId",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String knowledgeBaseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_type", length = 16, nullable = false)
  @Schema(hidden = true)
  private AiResourceScope scopeType;

  @Column(name = "tenant_id", length = 64)
  @Schema(hidden = true)
  private String tenantId;

  @Order(1)
  @Column(length = 512, nullable = false)
  @Schema(title = "i18n:ai.knowledge-documents.title.name",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String name;

  @Order(2)
  @Column(name = "file_name", length = 512)
  @Schema(title = "i18n:ai.knowledge-documents.title.fileName",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String fileName;

  @Order(3)
  @Column(name = "mime_type", length = 256)
  @Schema(title = "i18n:ai.knowledge-documents.title.mimeType",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String mimeType;

  @Column(name = "file_size")
  @Schema(title = "i18n:ai.knowledge-documents.title.fileSize",
      accessMode = Schema.AccessMode.READ_ONLY)
  private Long fileSize;

  @Column(name = "storage_object_id", length = 64)
  @Schema(title = "i18n:ai.knowledge-documents.title.storageObjectId",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String storageObjectId;

  @Column(name = "content_hash", length = 64)
  @Schema(hidden = true)
  private String contentHash;

  @Order(4)
  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", length = 16, nullable = false)
  @Schema(title = "i18n:ai.knowledge-documents.title.sourceType",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private AiKnowledgeDocumentSourceType sourceType;

  @Order(5)
  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  @Schema(title = "i18n:ai.knowledge-documents.title.status",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private AiKnowledgeDocumentStatus status;

  @Order(6)
  @Column(name = "chunk_count")
  @Schema(title = "i18n:ai.knowledge-documents.title.chunkCount",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Integer chunkCount;

  @Order(7)
  @Column(name = "processed_at")
  @Schema(title = "i18n:ai.knowledge-documents.title.processedAt",
      accessMode = Schema.AccessMode.READ_ONLY)
  private Instant processedAt;

  @Column(name = "error_message", length = 2048)
  @Schema(title = "i18n:ai.knowledge-documents.title.errorMessage",
      accessMode = Schema.AccessMode.READ_ONLY)
  private String errorMessage;

  @Column(name = "metadata_json", columnDefinition = "TEXT")
  @Schema(hidden = true)
  private String metadataJson;

  @JsonIgnore
  @Column(name = "extracted_text", columnDefinition = "TEXT")
  @Schema(hidden = true)
  private String extractedText;
}
