package org.simplepoint.plugin.ai.core.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.exception.AiProviderRequestException;
import org.simplepoint.plugin.ai.core.api.model.AiWorkbenchErrorCode;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService.GenerationStream;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.EventType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AiModelDebugControllerTest {

  private AiGenerationService generationService;

  private AiModelDebugController controller;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    generationService = mock(AiGenerationService.class);
    controller = controller(command -> {});
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void successfulPreparationKeepsEventStreamContract() {
    GenerationStream stream = mock(GenerationStream.class);
    when(generationService.prepareStream(any(GenerationRequest.class)))
        .thenReturn(stream);

    ResponseEntity<?> response = controller.stream(
        "model-1",
        new AiModelDebugController.DebugConversationRequest(List.of())
    );

    assertEquals(200, response.getStatusCode().value());
    assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
    assertInstanceOf(SseEmitter.class, response.getBody());
  }

  @Test
  void providerFailureReturnsStableJsonWithoutPrivateProse() throws Exception {
    String privateProse = "upstream credentials and response details";
    when(generationService.prepareStream(any(GenerationRequest.class)))
        .thenThrow(new AiProviderRequestException(401, privateProse));

    expectError(
        502,
        AiWorkbenchErrorCode.AI_MODEL_DEBUG_PROVIDER_FAILED,
        privateProse
    );
  }

  @Test
  void invalidRequestReturnsStableJsonWithoutPrivateProse() throws Exception {
    String privateProse = "model and tenant validation details";
    when(generationService.prepareStream(any(GenerationRequest.class)))
        .thenThrow(new IllegalArgumentException(privateProse));

    expectError(
        400,
        AiWorkbenchErrorCode.AI_MODEL_DEBUG_REQUEST_INVALID,
        privateProse
    );
  }

  @Test
  void unavailableOperationReturnsStableJsonWithoutPrivateProse()
      throws Exception {
    String privateProse = "provider state and secret configuration";
    when(generationService.prepareStream(any(GenerationRequest.class)))
        .thenThrow(new IllegalStateException(privateProse));

    expectError(
        400,
        AiWorkbenchErrorCode.AI_MODEL_DEBUG_OPERATION_UNAVAILABLE,
        privateProse
    );
  }

  @Test
  void unexpectedFailureReturnsStableServerError() throws Exception {
    String privateProse = "database topology and stack details";
    when(generationService.prepareStream(any(GenerationRequest.class)))
        .thenThrow(new RuntimeException(privateProse));

    expectError(
        500,
        AiWorkbenchErrorCode.AI_MODEL_DEBUG_FAILED,
        privateProse
    );
  }

  @Test
  void executorRejectionCancelsPreparedStreamAndReturnsBusyCode()
      throws Exception {
    String privateProse = "executor capacity and runtime internals";
    GenerationStream stream = mock(GenerationStream.class);
    when(generationService.prepareStream(any(GenerationRequest.class)))
        .thenReturn(stream);
    Executor rejectingExecutor = command -> {
      throw new RejectedExecutionException(privateProse);
    };
    controller = controller(rejectingExecutor);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    expectError(503, AiWorkbenchErrorCode.AI_MODEL_DEBUG_BUSY, privateProse);
    verify(stream).cancel();
  }

  @Test
  void accessDeniedRemainsAccessDenied() {
    when(generationService.prepareStream(any(GenerationRequest.class)))
        .thenThrow(new AccessDeniedException("denied"));

    assertThrows(
        AccessDeniedException.class,
        () -> controller.stream(
            "model-1",
            new AiModelDebugController.DebugConversationRequest(List.of())
        )
    );
  }

  @Test
  void errorEventsExposeOnlyStableGenerationCode() {
    GenerationEvent serverEvent = new GenerationEvent(
        "invocation-1",
        3,
        EventType.ERROR,
        null,
        null,
        null,
        null,
        null,
        null,
        "UPSTREAM_PRIVATE_CODE",
        "provider credential and endpoint details"
    );

    GenerationEvent clientEvent = AiModelDebugController.clientEvent(serverEvent);

    assertEquals(
        AiWorkbenchErrorCode.AI_MODEL_DEBUG_GENERATION_FAILED.name(),
        clientEvent.errorCode()
    );
    assertNull(clientEvent.errorMessage());
  }

  @Test
  void nonErrorEventsKeepTheirExistingContract() {
    GenerationEvent event = new GenerationEvent(
        "invocation-1",
        1,
        EventType.TEXT_DELTA,
        "hello",
        null,
        null,
        null,
        null,
        null,
        null,
        null
    );

    assertSame(event, AiModelDebugController.clientEvent(event));
  }

  private AiModelDebugController controller(final Executor executor) {
    return new AiModelDebugController(
        generationService,
        executor,
        new AiProperties()
    );
  }

  private void expectError(
      final int expectedStatus,
      final AiWorkbenchErrorCode expectedCode,
      final String privateProse
  ) throws Exception {
    mockMvc.perform(post("/workbench/models/model-1/debug/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"messages\":[]}"))
        .andExpect(status().is(expectedStatus))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(expectedCode.name()))
        .andExpect(content().string(not(containsString(privateProse))));
  }
}
