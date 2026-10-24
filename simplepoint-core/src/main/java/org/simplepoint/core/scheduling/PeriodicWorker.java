package org.simplepoint.core.scheduling;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * One owned, non-overlapping periodic loop; persistence and retry policy remain with its domain.
 */
public final class PeriodicWorker implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(PeriodicWorker.class);
  private final String name;
  private final Duration delay;
  private final Runnable work;
  private ScheduledExecutorService executor;

  /** Domain factories supply a bounded unit of work, without carrying a request context. */
  public PeriodicWorker(String name, Duration delay, Runnable work) {
    if (name == null
        || !name.matches("[a-z][a-z0-9-]{0,63}")
        || work == null
        || delay == null
        || delay.compareTo(Duration.ofMillis(100)) < 0
        || delay.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalArgumentException("Invalid periodic worker configuration");
    }
    this.name = name;
    this.delay = delay;
    this.work = work;
  }

  @Override
  public synchronized void start() {
    if (executor != null) {
      if (!executor.isTerminated()) {
        return;
      }
      executor = null;
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform()
                .name(name)
                .daemon()
                .inheritInheritableThreadLocals(false)
                .factory());
    executor.scheduleWithFixedDelay(
        () -> {
          try {
            work.run();
          } catch (RuntimeException failure) {
            LOG.warn("Worker {} deferred ({})", name, failure.getClass().getSimpleName());
          }
        },
        0,
        delay.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (executor == null) {
      return;
    }
    executor.shutdownNow();
    try {
      if (executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor = null;
      } else {
        // Keep the handle so a restart cannot create an overlapping worker after a slow shutdown.
        LOG.warn(
            "Worker {} is still stopping; its durable lease must expire before recovery", name);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return executor != null && !executor.isShutdown();
  }
}
