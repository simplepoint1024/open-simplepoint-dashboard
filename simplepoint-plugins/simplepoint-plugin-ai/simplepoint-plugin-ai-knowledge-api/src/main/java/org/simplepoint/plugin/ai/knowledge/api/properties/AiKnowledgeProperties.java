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

  private Integer maxChunksPerDocument = 5000;

  private Integer storedVectorDimensions = 2000;

  private Integer indexWorkerConcurrency = 2;

  private Integer indexClaimBatchSize = 2;

  private Long indexPollDelayMs = 1000L;

  private Integer indexLeaseSeconds = 300;

  private Integer indexMaxAttempts = 3;

  private Integer indexRetryInitialDelaySeconds = 10;

  /** Number of candidates retrieved per final result for hybrid fusion. */
  private Integer hybridCandidateMultiplier = 5;

  /** Reciprocal-rank-fusion constant used to smooth differences between rankings. */
  private Integer hybridRrfK = 60;

  /** Hard limit protecting the database from oversized candidate sets. */
  private Integer maxRetrievalCandidates = 1000;
}
