package org.simplepoint.plugin.ai.core.api.service;

import java.util.function.Consumer;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;

/**
 * Provider-neutral text and tool generation contract exposed to AI feature modules.
 */
public interface AiGenerationService {

  /**
   * Performs a synchronous generation.
   *
   * @param request normalized request
   * @return normalized result
   */
  GenerationResult generate(GenerationRequest request);

  /**
   * Resolves access and credentials in the request thread, returning a prepared stream operation.
   *
   * @param request normalized request
   * @return prepared one-shot stream
   */
  GenerationStream prepareStream(GenerationRequest request);

  /** A prepared one-shot stream that no longer reads the request authorization context. */
  @FunctionalInterface
  interface GenerationStream {

    /**
     * Consumes normalized stream events until completion or failure.
     *
     * @param consumer event consumer
     */
    void consume(Consumer<GenerationEvent> consumer);
  }
}
