package org.simplepoint.plugin.ai.core.rest.config;

import java.util.concurrent.Executor;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded executor used for provider SSE streams. */
@Configuration
public class AiInferenceConfiguration {

  /** Creates the bounded provider streaming executor. */
  @Bean(name = "aiInferenceExecutor", destroyMethod = "shutdown")
  public Executor aiInferenceExecutor(final AiProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    int corePoolSize = positive(properties.getInferenceCorePoolSize(), 4);
    int maxPoolSize = Math.max(
        corePoolSize,
        positive(properties.getInferenceMaxPoolSize(), 32)
    );
    executor.setThreadNamePrefix("ai-inference-");
    executor.setCorePoolSize(corePoolSize);
    executor.setMaxPoolSize(maxPoolSize);
    executor.setQueueCapacity(positive(properties.getInferenceQueueCapacity(), 200));
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
  }

  private static int positive(final Integer value, final int fallback) {
    return value != null && value > 0 ? value : fallback;
  }
}
