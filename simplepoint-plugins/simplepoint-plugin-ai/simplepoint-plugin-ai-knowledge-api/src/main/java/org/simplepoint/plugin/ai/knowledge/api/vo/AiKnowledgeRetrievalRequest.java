package org.simplepoint.plugin.ai.knowledge.api.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import lombok.Data;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeRetrievalMode;

/**
 * Knowledge retrieval request with optional per-query overrides.
 */
@Data
public class AiKnowledgeRetrievalRequest {

  @Schema(title = "检索问题", requiredMode = Schema.RequiredMode.REQUIRED)
  private String query;

  @Schema(title = "检索模式")
  private AiKnowledgeRetrievalMode mode;

  @Schema(title = "返回数量", minimum = "1", maximum = "100")
  private Integer topK;

  @Schema(title = "最低相关度", minimum = "0", maximum = "1")
  private Double scoreThreshold;

  @Schema(title = "限定文档 ID")
  private Set<String> documentIds;
}
