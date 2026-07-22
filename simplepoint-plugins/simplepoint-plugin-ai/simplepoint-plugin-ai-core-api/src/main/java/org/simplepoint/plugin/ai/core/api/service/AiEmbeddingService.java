package org.simplepoint.plugin.ai.core.api.service;

import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
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

  /**
   * Creates embeddings for a durable background job with an explicit ownership scope.
   *
   * @param invocationScope scope that owns the background work
   * @param tenantId tenant id when the scope is tenant-owned
   * @param modelDefinitionId local model definition id
   * @param inputs text inputs
   * @param dimensions optional requested output dimensions
   * @return ordered embedding result
   */
  default AiEmbeddingResult embedForScope(
      final AiResourceScope invocationScope,
      final String tenantId,
      final String modelDefinitionId,
      final List<String> inputs,
      final Integer dimensions
  ) {
    throw new UnsupportedOperationException("当前 Embedding 实现不支持后台作用域调用");
  }
}
