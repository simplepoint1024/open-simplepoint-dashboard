package org.simplepoint.plugin.ai.core.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.exception.AiGatewayAccessException;
import org.simplepoint.plugin.ai.core.api.exception.AiGatewayAccessException.FailureType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService.GatewaySession;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;
import org.simplepoint.plugin.ai.core.rest.gateway.AiCompatibilityMapper;
import org.simplepoint.plugin.ai.core.rest.gateway.OpenAiResponsesProtocol;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiCompatibleGatewayControllerTest {

  private AiGatewayAccessService accessService;

  private AiGenerationService generationService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    accessService = mock(AiGatewayAccessService.class);
    generationService = mock(AiGenerationService.class);
    ObjectMapper objectMapper = new ObjectMapper();
    AiCompatibleGatewayController controller = new AiCompatibleGatewayController(
        accessService,
        generationService,
        new AiCompatibilityMapper(objectMapper),
        new OpenAiResponsesProtocol(objectMapper),
        Runnable::run,
        new AiProperties()
    );
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void openAiErrorsUseProtocolNativeJson() throws Exception {
    rejectApiKey();

    mockMvc.perform(get("/v1/models")
            .header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error.type").value("authentication_error"))
        .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
  }

  @Test
  void anthropicErrorsUseProtocolNativeJson() throws Exception {
    rejectApiKey();

    mockMvc.perform(post("/v1/messages")
            .header("x-api-key", "invalid")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model":"test","max_tokens":16,
                 "messages":[{"role":"user","content":"ping"}]}
                """))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.type").value("error"))
        .andExpect(jsonPath("$.error.type").value("authentication_error"));
  }

  @Test
  void malformedJsonUsesOpenAiInvalidRequestEnvelope() throws Exception {
    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
        .andExpect(jsonPath("$.error.code").value("invalid_request"));
  }

  @Test
  void responsesErrorsUseOpenAiEnvelope() throws Exception {
    rejectApiKey();

    mockMvc.perform(post("/v1/responses")
            .header("Authorization", "Bearer invalid")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model":"test","input":"ping"}
                """))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error.type").value("authentication_error"))
        .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
  }

  @Test
  void responsesEndpointReturnsProtocolNativeObject() throws Exception {
    GatewaySession session = new GatewaySession(
        "key-1", "test", AiResourceScope.SYSTEM, null);
    when(accessService.authenticate("valid", "127.0.0.1")).thenReturn(session);
    when(accessService.resolveModelDefinitionId(session, "test")).thenReturn("definition-1");
    when(accessService.withSession(eq(session), any())).thenAnswer(invocation -> {
      Supplier<?> operation = invocation.getArgument(1);
      return operation.get();
    });
    when(generationService.generate(any(GenerationRequest.class))).thenReturn(
        new GenerationResult(
            "inv-1", "definition-1", "provider-model", "provider-request-1",
            List.of(new ContentBlock(
                ContentType.TEXT, "pong", null, null, null, null, null)),
            "completed", new TokenUsage(2, 1, 3, 0), 10,
            Instant.parse("2026-01-01T00:00:00Z")
        ));

    mockMvc.perform(post("/v1/responses")
            .header("Authorization", "Bearer valid")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model":"test","input":"ping"}
                """))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.object").value("response"))
        .andExpect(jsonPath("$.status").value("completed"))
        .andExpect(jsonPath("$.output[0].content[0].text").value("pong"))
        .andExpect(jsonPath("$.usage.total_tokens").value(3));
  }

  private void rejectApiKey() {
    when(accessService.authenticate(anyString(), anyString())).thenThrow(
        new AiGatewayAccessException(FailureType.AUTHENTICATION, "模型 API Key 无效")
    );
  }
}
