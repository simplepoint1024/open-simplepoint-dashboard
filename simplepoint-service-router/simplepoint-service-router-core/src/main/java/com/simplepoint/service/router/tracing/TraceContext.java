package com.simplepoint.service.router.tracing;

import java.util.UUID;

/**
 * Trace context helper.
 */
public final class TraceContext {

  private TraceContext() {
  }

  /**
   * Creates a trace id when no tracing system is installed.
   *
   * @return trace id
   */
  public static String newTraceId() {
    return UUID.randomUUID().toString();
  }
}
