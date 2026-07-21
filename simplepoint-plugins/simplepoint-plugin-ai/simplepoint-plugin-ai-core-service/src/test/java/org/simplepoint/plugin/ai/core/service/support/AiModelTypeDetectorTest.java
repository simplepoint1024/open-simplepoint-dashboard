package org.simplepoint.plugin.ai.core.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;

class AiModelTypeDetectorTest {

  private final AiModelTypeDetector detector = new AiModelTypeDetector();

  @Test
  void shouldDetectCommonModelCapabilities() {
    assertEquals(AiModelType.EMBEDDING, detector.detect("text-embedding-3-large"));
    assertEquals(AiModelType.RERANK, detector.detect("bge-reranker-v2"));
    assertEquals(AiModelType.IMAGE, detector.detect("dall-e-3"));
    assertEquals(AiModelType.AUDIO, detector.detect("whisper-1"));
    assertEquals(AiModelType.LLM, detector.detect("gpt-5"));
  }
}
