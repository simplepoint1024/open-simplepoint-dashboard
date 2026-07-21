package org.simplepoint.plugin.ai.core.api.vo;

import java.util.List;

/**
 * Embedding vectors returned by a configured provider.
 *
 * @param modelId remote model identifier
 * @param dimensions actual vector dimensions
 * @param vectors vectors ordered like the request inputs
 */
public record AiEmbeddingResult(
    String modelId,
    int dimensions,
    List<List<Double>> vectors
) {
}
