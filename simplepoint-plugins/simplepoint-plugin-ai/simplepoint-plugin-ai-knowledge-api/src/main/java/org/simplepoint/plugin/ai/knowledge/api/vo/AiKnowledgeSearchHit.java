package org.simplepoint.plugin.ai.knowledge.api.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One ranked knowledge chunk.
 *
 * @param chunkId chunk id
 * @param documentId document id
 * @param documentName source document name
 * @param chunkIndex zero-based chunk index
 * @param content chunk text
 * @param score combined score
 * @param vectorScore cosine similarity score
 * @param keywordScore full-text/trigram score
 * @param metadataJson optional source metadata
 */
public record AiKnowledgeSearchHit(
    @Schema(title = "分块 ID") String chunkId,
    @Schema(title = "文档 ID") String documentId,
    @Schema(title = "文档名称") String documentName,
    @Schema(title = "分块序号") Integer chunkIndex,
    @Schema(title = "分块内容") String content,
    @Schema(title = "综合相关度") Double score,
    @Schema(title = "向量相关度") Double vectorScore,
    @Schema(title = "关键词相关度") Double keywordScore,
    @Schema(title = "扩展元数据") String metadataJson
) {
}
