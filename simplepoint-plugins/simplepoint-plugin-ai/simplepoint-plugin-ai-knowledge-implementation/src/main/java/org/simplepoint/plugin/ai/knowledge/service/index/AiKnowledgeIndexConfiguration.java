package org.simplepoint.plugin.ai.knowledge.service.index;

import java.util.concurrent.Executor;
import org.simplepoint.plugin.ai.knowledge.api.properties.AiKnowledgeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Dedicated bounded executor for durable knowledge indexing. */
@Configuration
public class AiKnowledgeIndexConfiguration {

  /** Creates the knowledge-index worker executor. */
  @Bean(name = "aiKnowledgeIndexExecutor", destroyMethod = "shutdown")
  public Executor aiKnowledgeIndexExecutor(final AiKnowledgeProperties properties) {
    int concurrency = positive(properties.getIndexWorkerConcurrency(), 2);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("ai-knowledge-index-");
    executor.setCorePoolSize(concurrency);
    executor.setMaxPoolSize(concurrency);
    executor.setQueueCapacity(0);
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
  }

  private static int positive(final Integer value, final int fallback) {
    return value != null && value > 0 ? value : fallback;
  }
}
