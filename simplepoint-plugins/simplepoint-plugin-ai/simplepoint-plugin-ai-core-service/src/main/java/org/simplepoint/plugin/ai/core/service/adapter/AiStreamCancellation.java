package org.simplepoint.plugin.ai.core.service.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates cancellation of a provider request and its active response stream. */
final class AiStreamCancellation {

  private final AtomicBoolean cancelled = new AtomicBoolean();

  private final AtomicReference<CompletableFuture<?>> request = new AtomicReference<>();

  private final AtomicReference<InputStream> responseBody = new AtomicReference<>();

  void registerRequest(final CompletableFuture<?> future) {
    request.set(future);
    if (cancelled.get() && request.compareAndSet(future, null)) {
      future.cancel(true);
    }
  }

  void releaseRequest(final CompletableFuture<?> future) {
    request.compareAndSet(future, null);
  }

  void registerResponseBody(final InputStream input) {
    responseBody.set(input);
    if (cancelled.get() && responseBody.compareAndSet(input, null)) {
      closeQuietly(input);
    }
  }

  void releaseResponseBody(final InputStream input) {
    responseBody.compareAndSet(input, null);
  }

  void cancel() {
    if (!cancelled.compareAndSet(false, true)) {
      return;
    }
    CompletableFuture<?> future = request.getAndSet(null);
    if (future != null) {
      future.cancel(true);
    }
    closeQuietly(responseBody.getAndSet(null));
  }

  boolean isCancelled() {
    return cancelled.get();
  }

  void throwIfCancelled() {
    if (isCancelled()) {
      throw new CancellationException("AI 流式调用已取消");
    }
  }

  private static void closeQuietly(final InputStream input) {
    if (input == null) {
      return;
    }
    try {
      input.close();
    } catch (IOException ignored) {
      // Cancellation is best-effort and must not hide the original stream outcome.
    }
  }
}
