package org.simplepoint.plugin.ai.core.service.adapter;

import java.util.function.Consumer;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;

/** Internal SPI implemented by each provider generation protocol. */
interface AiGenerationAdapter {

  boolean supports(AiProviderType providerType);

  GenerationResult generate(AiRuntimeInvocation invocation);

  default void stream(
      final AiRuntimeInvocation invocation,
      final Consumer<GenerationEvent> consumer
  ) {
    stream(invocation, consumer, new AiStreamCancellation());
  }

  void stream(
      AiRuntimeInvocation invocation,
      Consumer<GenerationEvent> consumer,
      AiStreamCancellation cancellation
  );
}
