package org.simplepoint.plugin.ai.core.service.adapter;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.springframework.stereotype.Component;

/** Resolves the generation adapter for a configured provider protocol. */
@Component
public class AiGenerationAdapterRegistry {

  private final Map<AiProviderType, AiGenerationAdapter> adapters =
      new EnumMap<>(AiProviderType.class);

  /** Creates and validates the registry. */
  public AiGenerationAdapterRegistry(final List<AiGenerationAdapter> candidates) {
    for (AiProviderType type : AiProviderType.values()) {
      List<AiGenerationAdapter> matched = candidates.stream()
          .filter(candidate -> candidate.supports(type))
          .toList();
      if (matched.size() > 1) {
        throw new IllegalStateException("AI 生成协议存在多个适配器: " + type);
      }
      if (matched.size() == 1) {
        adapters.put(type, matched.getFirst());
      }
    }
  }

  AiGenerationAdapter require(final AiProviderDefinition provider) {
    AiProviderType type = explicitType(provider);
    AiGenerationAdapter adapter = adapters.get(type);
    if (adapter == null) {
      throw new IllegalStateException("暂不支持该供应商的生成协议: " + type);
    }
    return adapter;
  }

  private static AiProviderType explicitType(final AiProviderDefinition provider) {
    if (provider == null || provider.getProviderType() == null) {
      throw new IllegalStateException("AI 供应商协议不能为空");
    }
    return provider.getProviderType();
  }
}
