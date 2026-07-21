package org.simplepoint.plugin.ai.knowledge.api.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Safety limits and storage settings for knowledge processing.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = AiKnowledgeProperties.PREFIX)
public class AiKnowledgeProperties {

  public static final String PREFIX = "simplepoint.ai.knowledge";

  private Long maxUploadBytes = 20L * 1024L * 1024L;

  private Integer maxExtractedCharacters = 5_000_000;

  private Integer embeddingBatchSize = 64;

  private Integer storedVectorDimensions = 2000;
}
