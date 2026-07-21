package org.simplepoint.plugin.ai.core.api.service;

import java.util.List;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;

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
}
