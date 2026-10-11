package org.simplepoint.plugin.ai.core.api.service;

import java.util.List;
import java.util.Map;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiEmbeddingModelOption;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service contract for model definition management.
 */
public interface AiModelDefinitionService extends BaseService<AiModelDefinition, String> {

  /**
   * Lists enabled models available for invocation in the current scope.
   * Tenant contexts receive shared system models plus their own models.
   *
   * @return available models
   */
  List<AiModelDefinition> listAvailableModels();

  /** Pages available shared/owned embeddings without materializing model or provider entities. */
  Page<AiEmbeddingModelOption> embeddingModelOptions(Map<String, String> attributes, Pageable pageable);
}
