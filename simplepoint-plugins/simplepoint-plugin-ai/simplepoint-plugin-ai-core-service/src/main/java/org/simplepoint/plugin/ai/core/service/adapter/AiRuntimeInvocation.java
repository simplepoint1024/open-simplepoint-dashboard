package org.simplepoint.plugin.ai.core.service.adapter;

import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;

/** Runtime-only invocation data, including the decrypted credential. */
record AiRuntimeInvocation(
    String invocationId,
    AiProviderDefinition provider,
    AiModelDefinition model,
    GenerationRequest request,
    String apiKey,
    int timeoutSeconds
) {
}
