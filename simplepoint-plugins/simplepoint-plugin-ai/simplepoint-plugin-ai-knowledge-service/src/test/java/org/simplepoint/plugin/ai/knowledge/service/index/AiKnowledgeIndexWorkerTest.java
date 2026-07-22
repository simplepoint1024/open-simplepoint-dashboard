package org.simplepoint.plugin.ai.knowledge.service.index;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.knowledge.api.properties.AiKnowledgeProperties;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeIndexJobRepository;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeIndexJob;

class AiKnowledgeIndexWorkerTest {

  private AiKnowledgeIndexJobRepository jobRepository;

  private KnowledgeIndexCoordinator coordinator;

  private KnowledgeIndexProcessor processor;

  private AiKnowledgeProperties properties;

  @BeforeEach
  void setUp() {
    jobRepository = mock(AiKnowledgeIndexJobRepository.class);
    coordinator = mock(KnowledgeIndexCoordinator.class);
    processor = mock(KnowledgeIndexProcessor.class);
    properties = new AiKnowledgeProperties();
  }

  @Test
  void poll_executesClaimedJobAndCommitsPreparedGeneration() {
    AiKnowledgeIndexJob job = job();
    KnowledgeIndexPreparation preparation = new KnowledgeIndexPreparation(
        "doc-1", "kb-1", List.of(), null, null
    );
    when(jobRepository.claim(anyString(), eq(2), eq(Duration.ofSeconds(300))))
        .thenReturn(List.of(job));
    when(coordinator.markProcessing(job)).thenReturn(true);
    when(jobRepository.renew(job, Duration.ofSeconds(300))).thenReturn(true);
    when(processor.prepare(eq(job), any())).thenReturn(preparation);
    Executor directExecutor = Runnable::run;
    AiKnowledgeIndexWorker worker = worker(directExecutor);

    worker.poll();

    verify(coordinator).complete(job, preparation);
  }

  @Test
  void poll_releasesLeaseWhenLocalExecutorIsFull() {
    AiKnowledgeIndexJob job = job();
    when(jobRepository.claim(anyString(), eq(2), eq(Duration.ofSeconds(300))))
        .thenReturn(List.of(job));
    Executor rejectingExecutor = command -> {
      throw new RejectedExecutionException("full");
    };
    AiKnowledgeIndexWorker worker = worker(rejectingExecutor);

    worker.poll();

    verify(jobRepository).release(job);
  }

  @Test
  void poll_reschedulesTransientFailureWithExponentialDelay() {
    AiKnowledgeIndexJob job = job();
    when(jobRepository.claim(anyString(), eq(2), eq(Duration.ofSeconds(300))))
        .thenReturn(List.of(job));
    when(coordinator.markProcessing(job)).thenReturn(true);
    when(processor.prepare(eq(job), any())).thenThrow(new IllegalStateException("unavailable"));
    when(coordinator.fail(job, false, Duration.ofSeconds(10), "unavailable")).thenReturn(true);
    AiKnowledgeIndexWorker worker = worker(Runnable::run);

    worker.poll();

    verify(coordinator).fail(job, false, Duration.ofSeconds(10), "unavailable");
  }

  private AiKnowledgeIndexWorker worker(final Executor executor) {
    return new AiKnowledgeIndexWorker(
        jobRepository,
        coordinator,
        processor,
        properties,
        executor
    );
  }

  private static AiKnowledgeIndexJob job() {
    return new AiKnowledgeIndexJob(
        "doc-1",
        "kb-1",
        AiResourceScope.TENANT,
        "tenant-1",
        1L,
        1,
        false,
        "worker-1"
    );
  }
}
