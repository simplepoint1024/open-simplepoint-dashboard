package org.simplepoint.plugin.ai.knowledge.service.index;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.ai.knowledge.api.properties.AiKnowledgeProperties;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeIndexJobRepository;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeIndexJob;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls and executes durable knowledge-index jobs. */
@Slf4j
@Component
public class AiKnowledgeIndexWorker {

  private final AiKnowledgeIndexJobRepository jobRepository;

  private final KnowledgeIndexCoordinator coordinator;

  private final KnowledgeIndexProcessor processor;

  private final AiKnowledgeProperties properties;

  private final Executor executor;

  private final String workerId = UUID.randomUUID().toString();

  /** Creates the durable index worker. */
  public AiKnowledgeIndexWorker(
      final AiKnowledgeIndexJobRepository jobRepository,
      final KnowledgeIndexCoordinator coordinator,
      final KnowledgeIndexProcessor processor,
      final AiKnowledgeProperties properties,
      @Qualifier("aiKnowledgeIndexExecutor") final Executor executor
  ) {
    this.jobRepository = jobRepository;
    this.coordinator = coordinator;
    this.processor = processor;
    this.properties = properties;
    this.executor = executor;
  }

  /** Claims available work without blocking other service instances. */
  @Scheduled(fixedDelayString = "${simplepoint.ai.knowledge.index-poll-delay-ms:1000}")
  public void poll() {
    List<AiKnowledgeIndexJob> jobs;
    try {
      jobs = jobRepository.claim(
          workerId,
          positive(properties.getIndexClaimBatchSize(), 2),
          leaseDuration()
      );
    } catch (RuntimeException ex) {
      log.warn("Unable to claim AI knowledge index jobs: {}", ex.getMessage());
      return;
    }
    jobs.forEach(this::submit);
  }

  private void submit(final AiKnowledgeIndexJob job) {
    try {
      executor.execute(() -> execute(job));
    } catch (RejectedExecutionException ex) {
      jobRepository.release(job);
    }
  }

  private void execute(final AiKnowledgeIndexJob job) {
    try {
      if (!coordinator.markProcessing(job)) {
        return;
      }
      KnowledgeIndexPreparation preparation = processor.prepare(job, () -> renew(job));
      coordinator.complete(job, preparation);
    } catch (StaleIndexJobException ex) {
      log.debug("AI knowledge index job generation changed for document {}", job.documentId());
    } catch (RuntimeException ex) {
      handleFailure(job, ex);
    }
  }

  private void renew(final AiKnowledgeIndexJob job) {
    if (!jobRepository.renew(job, leaseDuration())) {
      throw new StaleIndexJobException();
    }
  }

  private void handleFailure(final AiKnowledgeIndexJob job, final RuntimeException error) {
    int maxAttempts = positive(properties.getIndexMaxAttempts(), 3);
    boolean terminal = job.attemptCount() >= maxAttempts;
    Duration retryDelay = retryDelay(job.attemptCount());
    boolean recorded;
    try {
      recorded = coordinator.fail(job, terminal, retryDelay, safeMessage(error));
    } catch (RuntimeException persistenceError) {
      log.warn(
          "Unable to record AI knowledge index failure for document {}: {}",
          job.documentId(),
          persistenceError.getMessage()
      );
      return;
    }
    if (recorded) {
      log.warn(
          "AI knowledge index job failed for document {} on attempt {}{}: {}",
          job.documentId(),
          job.attemptCount(),
          terminal ? " (terminal)" : "",
          safeMessage(error)
      );
    }
  }

  private Duration retryDelay(final int attemptCount) {
    long initial = positive(properties.getIndexRetryInitialDelaySeconds(), 10);
    int exponent = Math.max(0, Math.min(10, attemptCount - 1));
    return Duration.ofSeconds(Math.min(3600L, initial << exponent));
  }

  private Duration leaseDuration() {
    return Duration.ofSeconds(positive(properties.getIndexLeaseSeconds(), 300));
  }

  private static int positive(final Integer value, final int fallback) {
    return value != null && value > 0 ? value : fallback;
  }

  private static String safeMessage(final RuntimeException error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      return error.getClass().getSimpleName();
    }
    return message.length() <= 2000 ? message : message.substring(0, 2000);
  }

  private static final class StaleIndexJobException extends RuntimeException {
  }
}
