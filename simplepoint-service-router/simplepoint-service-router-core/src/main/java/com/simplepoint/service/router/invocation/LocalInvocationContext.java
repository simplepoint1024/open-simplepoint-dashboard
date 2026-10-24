package com.simplepoint.service.router.invocation;

/**
 * Installs trusted host context inside an isolated local invocation. Implementations must not copy
 * the browser request or its authentication into the service call.
 */
public interface LocalInvocationContext {

  /**
   * Captures trusted host bindings before switching threads. This must not install context or copy
   * browser authentication; the returned contribution opens only inside the invocation worker.
   */
  default LocalInvocationContext prepare() {
    return this;
  }

  /** Opens a context in the invocation worker, before any provider code runs. */
  Scope open();

  /** Restores context even when the provider fails or the caller stops waiting. */
  interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
