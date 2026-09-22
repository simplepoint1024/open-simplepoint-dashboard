package org.simplepoint.plugin.ai.core.api.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService.GatewaySession;

/** Lossless OpenAI HTTP transport with platform authorization and accounting. */
public interface AiNativeOpenAiGateway {

  /** Returns empty only when the selected provider requires protocol translation. */
  Optional<Exchange> create(GatewaySession session, String modelDefinitionId, String path,
      String payload, Map<String, String> headers);

  /** Routes response operations using a persisted, API-key-owned resource binding. */
  Exchange response(GatewaySession session, String responseId, String method, String suffix,
      String query, Map<String, String> headers);

  /** An opened upstream exchange. Status is known before downstream headers are committed. */
  interface Exchange extends AutoCloseable {
    int status();

    Map<String, List<String>> headers();

    boolean streaming();

    /** Consumes the response once, preserving JSON and SSE fields. */
    void consume(Consumer<byte[]> consumer);

    /** Cancels the request and closes its body, including before consumption starts. */
    @Override
    void close();
  }
}
