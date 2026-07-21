package org.simplepoint.plugin.ai.core.api.service;

import java.util.List;
import org.simplepoint.plugin.ai.core.api.vo.AiEmbeddingResult;

/**
 * Provider-neutral embedding invocation contract exposed to AI feature modules.
 */
public interface AiEmbeddingService {

  /**
   * Creates embeddings with a configured model visible in the current scope.
   *
   * @param modelDefinitionId local model definition id
   * @param inputs text inputs
   * @param dimensions optional requested output dimensions
   * @return ordered embedding result
   */
  AiEmbeddingResult embed(
      String modelDefinitionId,
      List<String> inputs,
      Integer dimensions
  );
}
