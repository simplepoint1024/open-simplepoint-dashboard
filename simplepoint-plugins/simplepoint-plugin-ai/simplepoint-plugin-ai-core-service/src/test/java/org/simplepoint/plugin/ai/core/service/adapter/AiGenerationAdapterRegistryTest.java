package org.simplepoint.plugin.ai.core.service.adapter;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;

class AiGenerationAdapterRegistryTest {

  @Test
  void routesOnlyOfficialOpenAiEndpointToResponsesApi() {
    AiGenerationAdapter openAi = adapter(AiProviderType.OPENAI);
    AiGenerationAdapter compatible = adapter(AiProviderType.OPENAI_COMPATIBLE);
    AiGenerationAdapter anthropic = adapter(AiProviderType.ANTHROPIC);
    AiGenerationAdapterRegistry registry = new AiGenerationAdapterRegistry(
        List.of(openAi, compatible, anthropic)
    );

    assertSame(openAi, registry.require(provider(
        AiProviderType.OPENAI, "https://api.openai.com/v1"
    )));
    assertSame(compatible, registry.require(provider(
        AiProviderType.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1"
    )));
    assertSame(compatible, registry.require(provider(
        AiProviderType.OPENAI_COMPATIBLE, "https://models.example.com/v1"
    )));
    assertSame(anthropic, registry.require(provider(
        AiProviderType.ANTHROPIC, "https://api.anthropic.com/v1"
    )));
  }

  private static AiGenerationAdapter adapter(final AiProviderType type) {
    AiGenerationAdapter adapter = mock(AiGenerationAdapter.class);
    when(adapter.supports(type)).thenReturn(true);
    return adapter;
  }

  private static AiProviderDefinition provider(
      final AiProviderType type,
      final String baseUrl
  ) {
    AiProviderDefinition provider = new AiProviderDefinition();
    provider.setProviderType(type);
    provider.setBaseUrl(baseUrl);
    return provider;
  }
}
