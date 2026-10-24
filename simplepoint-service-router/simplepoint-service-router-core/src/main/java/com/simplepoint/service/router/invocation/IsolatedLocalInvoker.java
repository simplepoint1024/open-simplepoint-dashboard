package com.simplepoint.service.router.invocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.exception.ServiceRouterException;
import com.simplepoint.service.router.registry.LocalRoutedService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes the wire contract on a fresh virtual thread without inherited request or transaction
 * state. Capacity stays reserved until all descendants actually exit, including after a timeout.
 */
public final class IsolatedLocalInvoker implements AutoCloseable {
  private final ServiceInvocationDispatcher dispatcher;
  private final ServiceRouterJsonCodec codec;
  private final List<LocalInvocationContext> contexts;
  private final Semaphore capacity;
  private final int maxDepth;
  private final long timeoutNanos;
  private final ThreadLocal<Call> active = new ThreadLocal<>();
  private final Object lifecycle = new Object();
  private final ConcurrentHashMap<Thread, FutureTask<byte[]>> workers = new ConcurrentHashMap<>();
  private boolean closed;

  /** Creates one host-wide bounded local transport. */
  public IsolatedLocalInvoker(
      ServiceInvocationDispatcher dispatcher,
      ObjectMapper mapper,
      List<LocalInvocationContext> contexts,
      int maxConcurrent,
      int maxDepth,
      Duration timeout) {
    if (maxConcurrent < 1
        || maxConcurrent > 4096
        || maxDepth < 1
        || maxDepth > 64
        || timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(Duration.ofDays(1)) > 0) {
      throw new IllegalArgumentException("Invalid local invocation bounds");
    }
    this.dispatcher = dispatcher;
    this.codec = new ServiceRouterJsonCodec(mapper);
    this.contexts = List.copyOf(contexts);
    this.capacity = new Semaphore(maxConcurrent);
    this.maxDepth = maxDepth;
    this.timeoutNanos = timeout.toNanos();
  }

  /** Runs once; uncertain completion is never retried or replaced by a fallback. */
  public RemoteResponse invoke(LocalRoutedService service, RemoteRequest request) {
    Call parent = active.get();
    if (parent != null && parent.deadline() - System.nanoTime() <= 0) {
      throw new ServiceRouterException("Local invocation deadline has expired");
    }
    // Freeze arguments and initialize a lazy provider on the caller's Spring creation thread.
    final byte[] body = codec.encode(request);
    Object bean = service.bean();
    final var target =
        new LocalRoutedService(service.metadata(), () -> bean, service.interfaceType());
    final var prepared = contexts.stream().map(LocalInvocationContext::prepare).toList();
    if (parent != null && parent.depth() >= maxDepth) {
      throw new ServiceRouterException("Local invocation depth limit exceeded");
    }
    if (parent == null && !capacity.tryAcquire()) {
      throw new ServiceRouterException("Local invocation capacity exhausted");
    }
    Lease lease = parent == null ? new Lease() : parent.lease();
    lease.remainingWorkers.incrementAndGet();
    Call call =
        new Call(
            lease,
            parent == null ? 1 : parent.depth() + 1,
            parent == null ? System.nanoTime() + timeoutNanos : parent.deadline());
    var started = new AtomicBoolean();
    var task =
        new FutureTask<byte[]>(
            () -> {
              started.set(true);
              try {
                active.set(call);
                return withContexts(prepared, 0, target, body);
              } finally {
                active.remove();
                lease.release();
              }
            });
    try {
      synchronized (lifecycle) {
        if (closed) {
          throw new ServiceRouterException("Local invocation transport is closed");
        }
        Thread worker =
            Thread.ofVirtual()
                .name("service-router-local-")
                .inheritInheritableThreadLocals(false)
                .unstarted(
                    () -> {
                      try {
                        task.run();
                        if (!started.get()) {
                          lease.release();
                        }
                      } finally {
                        workers.remove(Thread.currentThread());
                      }
                    });
        workers.put(worker, task);
        try {
          worker.start();
        } catch (RuntimeException | Error failure) {
          workers.remove(worker);
          throw failure;
        }
      }
    } catch (RuntimeException | Error failure) {
      lease.release();
      throw failure;
    }
    try {
      long remaining = call.deadline() - System.nanoTime();
      if (remaining <= 0) {
        throw new TimeoutException();
      }
      return codec.decodeResponse(task.get(remaining, TimeUnit.NANOSECONDS));
    } catch (InterruptedException failure) {
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new ServiceRouterException(
          "Local invocation interrupted; completion is uncertain", failure);
    } catch (TimeoutException failure) {
      task.cancel(true);
      throw new ServiceRouterException(
          "Local invocation timed out; completion is uncertain", failure);
    } catch (CancellationException failure) {
      throw new ServiceRouterException(
          "Local invocation stopped; completion is uncertain", failure);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof Error error) {
        throw error;
      }
      throw new ServiceRouterException("Local invocation boundary failed", failure.getCause());
    }
  }

  /**
   * Stops accepting calls and requests interruption; this never reports an interrupted write as
   * rolled back.
   */
  @Override
  public void close() {
    List<FutureTask<byte[]>> pending;
    synchronized (lifecycle) {
      closed = true;
      pending = List.copyOf(workers.values());
    }
    pending.forEach(task -> task.cancel(true));
  }

  private byte[] withContexts(
      List<LocalInvocationContext> prepared, int index, LocalRoutedService service, byte[] body) {
    if (index == prepared.size()) {
      if (Thread.currentThread().isInterrupted()
          || active.get().deadline() - System.nanoTime() <= 0) {
        throw new ServiceRouterException("Local invocation stopped before provider execution");
      }
      return dispatcher.dispatchJson(service, body);
    }
    try (var scope = prepared.get(index).open()) {
      if (scope == null) {
        throw new ServiceRouterException("Local invocation context did not open a scope");
      }
      return withContexts(prepared, index + 1, service, body);
    }
  }

  private final class Lease {
    private final AtomicInteger remainingWorkers = new AtomicInteger();

    void release() {
      if (remainingWorkers.decrementAndGet() == 0) {
        capacity.release();
      }
    }
  }

  private record Call(Lease lease, int depth, long deadline) {}
}
