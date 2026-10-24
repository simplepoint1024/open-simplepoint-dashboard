package org.simplepoint.core.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PeriodicWorkerTest {
  @Test
  void retriesAfterFailureWithoutInheritingRequestContextAndStopsTheActualThread()
      throws Exception {
    var inherited = new InheritableThreadLocal<String>();
    inherited.set("request-scope");
    var calls = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var observed = new AtomicReference<String>();
    var workerThread = new AtomicReference<Thread>();
    var worker =
        new PeriodicWorker(
            "test-periodic-worker",
            Duration.ofMillis(100),
            () -> {
              observed.set(inherited.get());
              workerThread.set(Thread.currentThread());
              if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("retry");
              }
              entered.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
              } finally {
                stopped.countDown();
              }
            });
    try {
      worker.start();
      worker.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(worker.isRunning());
      assertNull(observed.get());
      assertEquals(2, calls.get());
    } finally {
      worker.stop();
      inherited.remove();
    }
    assertFalse(worker.isRunning());
    assertTrue(stopped.await(1, TimeUnit.SECONDS));
    workerThread.get().join(1000);
    assertFalse(workerThread.get().isAlive());
  }
}
