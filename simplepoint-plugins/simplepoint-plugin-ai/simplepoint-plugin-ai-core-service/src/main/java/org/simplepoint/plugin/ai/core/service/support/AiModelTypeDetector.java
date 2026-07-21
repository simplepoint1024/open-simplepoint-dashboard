package org.simplepoint.plugin.ai.core.service.support;

import java.util.Locale;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.springframework.stereotype.Component;

/**
 * Infers a common model capability from vendor model identifiers.
 */
@Component
public class AiModelTypeDetector {

  /**
   * Detects the likely model capability.
   *
   * @param modelId vendor model identifier
   * @return detected capability
   */
  public AiModelType detect(final String modelId) {
    String value = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
    if (containsAny(value, "embedding", "embed")) {
      return AiModelType.EMBEDDING;
    }
    if (containsAny(value, "rerank", "ranker")) {
      return AiModelType.RERANK;
    }
    if (value.contains("moderation")) {
      return AiModelType.MODERATION;
    }
    if (containsAny(value, "dall-e", "image", "imagen")) {
      return AiModelType.IMAGE;
    }
    if (containsAny(value, "audio", "whisper", "tts", "transcribe", "realtime")) {
      return AiModelType.AUDIO;
    }
    if (containsAny(value, "sora", "video", "vision")) {
      return AiModelType.MULTIMODAL;
    }
    return AiModelType.LLM;
  }

  private static boolean containsAny(final String value, final String... candidates) {
    for (String candidate : candidates) {
      if (value.contains(candidate)) {
        return true;
      }
    }
    return false;
  }
}
