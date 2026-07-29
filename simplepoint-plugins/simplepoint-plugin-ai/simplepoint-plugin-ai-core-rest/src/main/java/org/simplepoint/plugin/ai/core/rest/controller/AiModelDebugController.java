package org.simplepoint.plugin.ai.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.simplepoint.plugin.ai.core.api.constants.AiPaths;
import org.simplepoint.plugin.ai.core.api.exception.AiProviderRequestException;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService.GenerationStream;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Minimal conversation-only debugger bound to a model selected in the catalog.
 */
@RestController
@RequestMapping(AiPaths.MODELS)
@Tag(name = "AI模型调试", description = "从模型管理页面发起无设置项的临时对话")
public class AiModelDebugController {

  private static final MediaType TEXT_PLAIN_UTF8 =
      MediaType.parseMediaType("text/plain;charset=UTF-8");

  private final AiGenerationService generationService;

  private final Executor inferenceExecutor;

  private final AiProperties properties;

  /** Creates the model debug controller. */
  public AiModelDebugController(
      final AiGenerationService generationService,
      @Qualifier("aiInferenceExecutor") final Executor inferenceExecutor,
      final AiProperties properties
  ) {
    this.generationService = generationService;
    this.inferenceExecutor = inferenceExecutor;
    this.properties = properties;
  }

  /**
   * Streams a temporary conversation through the selected model. Model settings
   * are intentionally not accepted from this endpoint.
   */
  @PostMapping(
      value = "/{modelDefinitionId}/debug/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE
  )
  @PreAuthorize("hasRole('Administrator') or hasAuthority('ai.workbench.models.debug')")
  @Operation(summary = "调试指定 AI 模型")
  public ResponseEntity<?> stream(
      @PathVariable("modelDefinitionId") final String modelDefinitionId,
      @RequestBody final DebugConversationRequest request
  ) {
    try {
      GenerationRequest generationRequest = new GenerationRequest(
          modelDefinitionId,
          null,
          request == null || request.messages() == null ? List.of() : request.messages(),
          null,
          null,
          null,
          List.of(),
          null
      );
      GenerationStream stream = generationService.prepareStream(generationRequest);
      SseEmitter emitter = new SseEmitter(timeout());
      AtomicBoolean terminal = new AtomicBoolean();
      emitter.onTimeout(() -> cancel(stream, terminal));
      emitter.onError(error -> cancel(stream, terminal));
      emitter.onCompletion(() -> cancel(stream, terminal));
      try {
        inferenceExecutor.execute(() -> consume(stream, emitter, terminal));
      } catch (RejectedExecutionException ex) {
        stream.cancel();
        throw ex;
      }
      return ResponseEntity.ok()
          .contentType(MediaType.TEXT_EVENT_STREAM)
          .body(emitter);
    } catch (AiProviderRequestException ex) {
      return ResponseEntity.status(502)
          .contentType(TEXT_PLAIN_UTF8)
          .body(ex.getMessage());
    } catch (RejectedExecutionException ex) {
      return ResponseEntity.status(503)
          .contentType(TEXT_PLAIN_UTF8)
          .body("AI 调试任务繁忙，请稍后重试");
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return ResponseEntity.badRequest()
          .contentType(TEXT_PLAIN_UTF8)
          .body(ex.getMessage());
    }
  }

  private static void consume(
      final GenerationStream stream,
      final SseEmitter emitter,
      final AtomicBoolean terminal
  ) {
    try {
      stream.consume(event -> send(emitter, event));
      terminal.set(true);
      emitter.complete();
    } catch (CancellationException ex) {
      terminal.set(true);
      emitter.complete();
    } catch (RuntimeException ex) {
      terminal.set(true);
      emitter.completeWithError(ex);
    }
  }

  private static void cancel(final GenerationStream stream, final AtomicBoolean terminal) {
    if (terminal.compareAndSet(false, true)) {
      stream.cancel();
    }
  }

  private static void send(final SseEmitter emitter, final GenerationEvent event) {
    try {
      emitter.send(SseEmitter.event()
          .id(Long.toString(event.sequence()))
          .name(event.type().name().toLowerCase(Locale.ROOT))
          .data(event));
    } catch (IOException ex) {
      CancellationException cancellation = new CancellationException("AI 调试连接已断开");
      cancellation.initCause(ex);
      throw cancellation;
    }
  }

  private long timeout() {
    Long configured = properties.getStreamingTimeoutMs();
    return configured != null && configured > 0 ? configured : 300_000L;
  }

  /** Conversation input accepted by the minimal debugger. */
  public record DebugConversationRequest(List<Message> messages) {
  }
}
