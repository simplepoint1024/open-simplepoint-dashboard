package org.simplepoint.plugin.ai.core.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;

class AiModelTypeDetectorTest {

  private final AiModelTypeDetector detector = new AiModelTypeDetector();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void resolvesCapabilitiesFromProviderMetadata() throws Exception {
    assertEquals(AiModelType.EMBEDDING, detect("{\"task\":\"text-embedding\"}"));
    assertEquals(AiModelType.RERANK, detect("{\"endpoints\":[\"rerank\"]}"));
    assertEquals(AiModelType.MULTIMODAL, detect(
        "{\"input_modalities\":[\"text\",\"image\"],\"capabilities\":[\"chat\"]}"
    ));
    assertEquals(AiModelType.LLM, detect(
        "{\"supportedGenerationMethods\":[\"generateContent\"]}"
    ));
  }

  @Test
  void resolvesCapabilityFromDedicatedDiscoveryEndpoint() throws Exception {
    assertEquals(
        AiModelType.EMBEDDING,
        detector.detect(objectMapper.readTree("{}"), URI.create("https://api.test/embeddings/models"))
    );
  }

  @Test
  void resolvesDashScopeCapabilityCodesAndInferenceModalities() throws Exception {
    assertEquals(AiModelType.LLM, detect("{\"capabilities\":[\"TG\",\"Reasoning\"]}"));
    assertEquals(AiModelType.EMBEDDING, detect("{\"capabilities\":[\"TR\"]}"));
    assertEquals(AiModelType.IMAGE, detect("{\"capabilities\":[\"IG\"]}"));
    assertEquals(AiModelType.AUDIO, detect("{\"capabilities\":[\"ASR\"]}"));
    assertEquals(AiModelType.MULTIMODAL, detect("{\"capabilities\":[\"VU\"]}"));
    assertEquals(AiModelType.MULTIMODAL, detect(
        "{\"inference_metadata\":{\"request_modality\":[\"Text\",\"Image\"],"
            + "\"response_modality\":[\"Text\"]}}"
    ));
  }

  @Test
  void neverUsesModelIdentifierOrDisplayNameAsCapabilitySignal() throws Exception {
    assertNull(detector.detect(
        objectMapper.readTree(
            "{\"id\":\"text-embedding-3-large\",\"displayName\":\"Embedding model\"}"
        ),
        URI.create("https://api.test/v1/models")
    ));
  }

  private AiModelType detect(final String json) throws Exception {
    return detector.detect(
        objectMapper.readTree(json),
        URI.create("https://api.test/v1/models")
    );
  }
}
