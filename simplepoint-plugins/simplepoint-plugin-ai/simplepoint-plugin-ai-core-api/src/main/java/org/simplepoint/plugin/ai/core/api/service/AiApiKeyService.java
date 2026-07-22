package org.simplepoint.plugin.ai.core.api.service;

import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.ai.core.api.entity.AiApiKey;

/** Management operations for API keys issued to model gateway clients. */
public interface AiApiKeyService extends BaseService<AiApiKey, String> {

  /** Replaces an API key secret and returns the new secret exactly once. */
  AiApiKey rotate(String id);
}
