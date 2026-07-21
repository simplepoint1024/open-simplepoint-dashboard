package org.simplepoint.plugin.ai.knowledge.api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Request for adding a plain-text document.
 */
@Data
public class AiKnowledgeTextDocumentRequest {

  @Schema(title = "文档名称", maxLength = 512)
  private String name;

  @Schema(title = "文档内容")
  private String content;

  @Schema(title = "扩展元数据 JSON")
  private String metadataJson;
}
