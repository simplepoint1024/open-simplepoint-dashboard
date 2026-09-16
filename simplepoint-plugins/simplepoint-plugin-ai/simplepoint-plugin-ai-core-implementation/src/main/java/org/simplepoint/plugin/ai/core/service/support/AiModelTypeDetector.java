package org.simplepoint.plugin.ai.core.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.springframework.stereotype.Component;

/** Resolves model capabilities from discovery metadata and endpoint semantics. */
@Component
public class AiModelTypeDetector {

  private static final List<String> SIGNAL_FIELDS = List.of(
      "model_type", "modelType", "task", "pipeline_tag", "pipelineTag",
      "capability", "capabilities", "endpoints", "features",
      "supportedGenerationMethods", "supported_actions", "modalities",
      "input_modalities", "output_modalities"
  );

  /**
   * Resolves the model type without inspecting the model identifier or display name.
   *
   * @param metadata model object returned by the discovery endpoint
   * @param discoveryUri configured discovery endpoint
   * @return resolved type, or {@code null} when neither source declares a capability
   */
  public AiModelType detect(final JsonNode metadata, final URI discoveryUri) {
    List<String> signals = new ArrayList<>();
    if (metadata != null && metadata.isObject()) {
      SIGNAL_FIELDS.forEach(field -> collect(metadata.get(field), signals));
      collect(metadata.path("architecture").get("modality"), signals);
      collect(metadata.path("architecture").get("input_modalities"), signals);
      collect(metadata.path("architecture").get("output_modalities"), signals);
      collect(metadata.path("inference_metadata").get("request_modality"), signals);
      collect(metadata.path("inference_metadata").get("response_modality"), signals);
    }
    AiModelType metadataType = fromSignals(signals);
    return metadataType == null ? fromDiscoveryUri(discoveryUri) : metadataType;
  }

  private static AiModelType fromSignals(final List<String> signals) {
    if (hasExact(signals, "tr", "me")) {
      return AiModelType.EMBEDDING;
    }
    if (hasExact(signals, "ig", "vg", "3d-generation")) {
      return AiModelType.IMAGE;
    }
    if (hasExact(signals, "asr", "tts", "realtime-asr", "realtime-text-to-speech",
        "realtime-audio-translate")) {
      return AiModelType.AUDIO;
    }
    if (hasExact(signals, "vu", "realtime-omni", "multimodal-omni",
        "realtime-chatting")) {
      return AiModelType.MULTIMODAL;
    }
    if (hasExact(signals, "tg", "reasoning")) {
      return AiModelType.LLM;
    }
    final boolean text = hasAny(signals, "text", "chat", "completion", "generatecontent",
        "generatemessage", "text-generation", "chat-completions");
    final boolean image = hasAny(signals, "image", "vision", "image-input");
    if (hasAny(signals, "rerank", "reranking", "rank")) {
      return AiModelType.RERANK;
    }
    if (hasAny(signals, "embedding", "embeddings", "embed", "embedcontent",
        "text-embedding")) {
      return AiModelType.EMBEDDING;
    }
    if (hasAny(signals, "moderation", "safety", "content-moderation")) {
      return AiModelType.MODERATION;
    }
    if (hasAny(signals, "multimodal", "multi-modal") || (text && image)) {
      return AiModelType.MULTIMODAL;
    }
    if (hasAny(signals, "audio", "speech", "transcription", "text-to-speech")) {
      return AiModelType.AUDIO;
    }
    if (hasAny(signals, "image-generation", "text-to-image") || image) {
      return AiModelType.IMAGE;
    }
    return text ? AiModelType.LLM : null;
  }

  private static AiModelType fromDiscoveryUri(final URI uri) {
    if (uri == null || uri.getPath() == null) {
      return null;
    }
    String path = uri.getPath().toLowerCase(Locale.ROOT);
    if (containsAny(path, "/rerank", "/ranker")) {
      return AiModelType.RERANK;
    }
    if (containsAny(path, "/embedding", "/embed/")) {
      return AiModelType.EMBEDDING;
    }
    if (containsAny(path, "/moderation", "/safety")) {
      return AiModelType.MODERATION;
    }
    if (containsAny(path, "/multimodal", "/vision")) {
      return AiModelType.MULTIMODAL;
    }
    if (containsAny(path, "/audio", "/speech", "/transcription")) {
      return AiModelType.AUDIO;
    }
    if (containsAny(path, "/image", "/images")) {
      return AiModelType.IMAGE;
    }
    if (containsAny(path, "/chat/models", "/generation/models", "/llm/models")) {
      return AiModelType.LLM;
    }
    return null;
  }

  private static void collect(final JsonNode node, final List<String> values) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return;
    }
    if (node.isValueNode()) {
      String value = node.asText("").trim().toLowerCase(Locale.ROOT);
      if (!value.isEmpty() && !"model".equals(value)) {
        values.add(value);
      }
      return;
    }
    if (node.isArray()) {
      node.forEach(value -> collect(value, values));
      return;
    }
    node.properties().forEach(entry -> {
      if (entry.getValue().asBoolean(false)) {
        values.add(entry.getKey().toLowerCase(Locale.ROOT));
      } else {
        collect(entry.getValue(), values);
      }
    });
  }

  private static boolean hasAny(final List<String> values, final String... candidates) {
    for (String value : values) {
      for (String candidate : candidates) {
        if (value.equals(candidate) || value.contains(candidate)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasExact(final List<String> values, final String... candidates) {
    for (String value : values) {
      for (String candidate : candidates) {
        if (value.equals(candidate)) {
          return true;
        }
      }
    }
    return false;
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
