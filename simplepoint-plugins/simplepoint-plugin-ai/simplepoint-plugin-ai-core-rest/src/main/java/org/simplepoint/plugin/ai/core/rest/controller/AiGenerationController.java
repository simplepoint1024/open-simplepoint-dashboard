package org.simplepoint.plugin.ai.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.core.api.constants.AiPaths;
import org.simplepoint.plugin.ai.core.api.exception.AiProviderRequestException;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService.GenerationStream;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Provider-neutral synchronous and streaming generation endpoints. */
@RestController
@RequestMapping({AiPaths.PLATFORM_INFERENCE, AiPaths.TENANT_INFERENCE})
@Tag(name = "AI统一推理", description = "以统一协议调用 OpenAI、Anthropic 和兼容模型")
public class AiGenerationController {

  private static final MediaType TEXT_PLAIN_UTF8 =
      MediaType.parseMediaType("text/plain;charset=UTF-8");

  private final AiGenerationService generationService;

  private final Executor inferenceExecutor;

  private final AiProperties properties;

  /** Creates the generation controller. */
  public AiGenerationController(
      final AiGenerationService generationService,
      @Qualifier("aiInferenceExecutor") final Executor inferenceExecutor,
      final AiProperties properties
  ) {
    this.generationService = generationService;
    this.inferenceExecutor = inferenceExecutor;
    this.properties = properties;
  }

  /** Performs one synchronous generation. */
  @PostMapping("/generate")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.inference.invoke', 'ai.inference.invoke')"
  )
  @Operation(summary = "同步调用 AI 生成模型")
  public Response<?> generate(@RequestBody final GenerationRequest request) {
    try {
      return Response.okay(generationService.generate(request));
    } catch (AiProviderRequestException ex) {
      return Response.of(ResponseEntity.status(502)
          .contentType(TEXT_PLAIN_UTF8).body(ex.getMessage()));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return Response.of(ResponseEntity.badRequest()
          .contentType(TEXT_PLAIN_UTF8).body(ex.getMessage()));
    }
  }

  /** Streams normalized generation events using server-sent events. */
  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.inference.invoke', 'ai.inference.invoke')"
  )
  @Operation(summary = "流式调用 AI 生成模型")
  public ResponseEntity<?> stream(@RequestBody final GenerationRequest request) {
    try {
      GenerationStream stream = generationService.prepareStream(request);
      SseEmitter emitter = new SseEmitter(timeout());
      inferenceExecutor.execute(() -> consume(stream, emitter));
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
          .body("AI 推理任务繁忙，请稍后重试");
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return ResponseEntity.badRequest()
          .contentType(TEXT_PLAIN_UTF8)
          .body(ex.getMessage());
    }
  }

  private static void consume(final GenerationStream stream, final SseEmitter emitter) {
    try {
      stream.consume(event -> send(emitter, event));
      emitter.complete();
    } catch (RuntimeException ex) {
      emitter.completeWithError(ex);
    }
  }

  private static void send(final SseEmitter emitter, final GenerationEvent event) {
    try {
      emitter.send(SseEmitter.event()
          .id(Long.toString(event.sequence()))
          .name(event.type().name().toLowerCase(Locale.ROOT))
          .data(event));
    } catch (IOException ex) {
      throw new IllegalStateException("AI 流式连接已断开", ex);
    }
  }

  private long timeout() {
    Long configured = properties.getStreamingTimeoutMs();
    return configured != null && configured > 0 ? configured : 300_000L;
  }
}
