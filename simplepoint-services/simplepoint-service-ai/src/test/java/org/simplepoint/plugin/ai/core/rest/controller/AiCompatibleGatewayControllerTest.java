package org.simplepoint.plugin.ai.core.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.exception.AiGatewayAccessException;
import org.simplepoint.plugin.ai.core.api.exception.AiGatewayAccessException.FailureType;
import org.simplepoint.plugin.ai.core.api.exception.AiOpenAiRequestException;
import org.simplepoint.plugin.ai.core.api.exception.AiProviderRequestException;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService.GatewaySession;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService.GenerationStream;
import org.simplepoint.plugin.ai.core.api.service.AiNativeOpenAiGateway;
import org.simplepoint.plugin.ai.core.api.service.AiNativeOpenAiGateway.Exchange;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.EventType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;
import org.simplepoint.plugin.ai.core.rest.gateway.AiCompatibilityMapper;
import org.simplepoint.plugin.ai.core.rest.gateway.OpenAiResponsesProtocol;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiCompatibleGatewayControllerTest {

  private AiGatewayAccessService accessService;

  private AiGenerationService generationService;

  private MockMvc mockMvc;

  private AiNativeOpenAiGateway nativeGateway;

  @BeforeEach
  void setUp() {
    accessService = mock(AiGatewayAccessService.class);
    generationService = mock(AiGenerationService.class);
    nativeGateway = mock(AiNativeOpenAiGateway.class);
    ObjectMapper objectMapper = new ObjectMapper();
    AiCompatibleGatewayController controller = new AiCompatibleGatewayController(
        accessService,
        generationService,
        new AiCompatibilityMapper(objectMapper),
        new OpenAiResponsesProtocol(objectMapper),
        Runnable::run,
        new AiProperties(),
        nativeGateway
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
        .andExpect(jsonPath("$.error.code").value("invalid_api_key"))
        .andExpect(jsonPath("$.error.message")
            .value("模型 API Key 无效或缺失，请检查认证信息"))
        .andExpect(content().string(not(containsString("private-auth-detail"))));
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
        .andExpect(jsonPath("$.error.type").value("authentication_error"))
        .andExpect(jsonPath("$.error.message")
            .value("模型 API Key 无效或缺失，请检查认证信息"))
        .andExpect(content().string(not(containsString("private-auth-detail"))));
  }

  @Test
  void malformedJsonUsesOpenAiInvalidRequestEnvelope() throws Exception {
    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
        .andExpect(jsonPath("$.error.code").value("invalid_request"))
        .andExpect(jsonPath("$.error.message")
            .value("请求参数无效，请检查请求格式和必填字段"));
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
    allowApiKey();
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

  @Test
  void invalidRequestDoesNotExposeValidationException() throws Exception {
    allowApiKey();
    when(generationService.generate(any(GenerationRequest.class))).thenThrow(
        new IllegalArgumentException("private-validation-detail token=secret")
    );

    mockMvc.perform(post("/v1/chat/completions")
            .header("Authorization", "Bearer valid")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model":"test","messages":[{"role":"user","content":"ping"}]}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
        .andExpect(jsonPath("$.error.code").value("invalid_request"))
        .andExpect(jsonPath("$.error.message")
            .value("请求参数无效，请检查请求格式和必填字段"))
        .andExpect(content().string(not(containsString("private-validation-detail"))))
        .andExpect(content().string(not(containsString("token=secret"))));
  }

  @Test
  void openAiProviderFailureDoesNotExposeVendorResponse() throws Exception {
    allowApiKey();
    when(generationService.generate(any(GenerationRequest.class))).thenThrow(
        new AiProviderRequestException(429, "vendor raw response api_key=secret")
    );

    mockMvc.perform(post("/v1/responses")
            .header("Authorization", "Bearer valid")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model":"test","input":"ping"}
                """))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.type").value("api_error"))
        .andExpect(jsonPath("$.error.code").value("upstream_error"))
        .andExpect(jsonPath("$.error.message")
            .value("上游模型服务调用失败，请稍后重试"))
        .andExpect(content().string(not(containsString("vendor raw response"))))
        .andExpect(content().string(not(containsString("api_key=secret"))));
  }

  @Test
  void anthropicProviderFailureDoesNotExposeVendorResponse() throws Exception {
    allowApiKey();
    when(generationService.generate(any(GenerationRequest.class))).thenThrow(
        new AiProviderRequestException(400, "anthropic raw response bearer secret")
    );

    mockMvc.perform(post("/v1/messages")
            .header("x-api-key", "valid")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model":"test","max_tokens":16,
                 "messages":[{"role":"user","content":"ping"}]}
                """))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.type").value("error"))
        .andExpect(jsonPath("$.error.type").value("api_error"))
        .andExpect(jsonPath("$.error.message")
            .value("上游模型服务调用失败，请稍后重试"))
        .andExpect(content().string(not(containsString("anthropic raw response"))))
        .andExpect(content().string(not(containsString("bearer secret"))));
  }

  @Test
  void openAiStreamDoesNotExposeGenerationErrorDetail() throws Exception {
    allowApiKey();
    when(generationService.prepareStream(any(GenerationRequest.class)))
        .thenReturn(errorStream("provider stream body api_key=secret"));

    MvcResult result = mockMvc.perform(post("/v1/chat/completions")
            .header("Authorization", "Bearer valid")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model":"test","stream":true,
                 "messages":[{"role":"user","content":"ping"}]}
                """))
        .andExpect(request().asyncStarted())
        .andReturn();

    mockMvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andExpect(content().string(containsString("模型生成失败，请稍后重试")))
        .andExpect(content().string(containsString("generation_failed")))
        .andExpect(content().string(not(containsString("provider stream body"))))
        .andExpect(content().string(not(containsString("api_key=secret"))));
  }

  @Test
  void anthropicStreamDoesNotExposeGenerationErrorDetail() throws Exception {
    allowApiKey();
    when(generationService.prepareStream(any(GenerationRequest.class)))
        .thenReturn(errorStream("anthropic stream body bearer secret"));

    MvcResult result = mockMvc.perform(post("/v1/messages")
            .header("x-api-key", "valid")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model":"test","stream":true,"max_tokens":16,
                 "messages":[{"role":"user","content":"ping"}]}
                """))
        .andExpect(request().asyncStarted())
        .andReturn();

    mockMvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andExpect(content().string(containsString("模型生成失败，请稍后重试")))
        .andExpect(content().string(not(containsString("anthropic stream body"))))
        .andExpect(content().string(not(containsString("bearer secret"))));
  }

  private GatewaySession allowApiKey() {
    GatewaySession session = new GatewaySession(
        "key-1", "test", AiResourceScope.SYSTEM, null);
    when(accessService.authenticate("valid", "127.0.0.1")).thenReturn(session);
    when(accessService.resolveModelDefinitionId(session, "test")).thenReturn("definition-1");
    when(accessService.withSession(eq(session), any())).thenAnswer(invocation -> {
      Supplier<?> operation = invocation.getArgument(1);
      return operation.get();
    });
    return session;
  }

  private static GenerationStream errorStream(final String privateDetail) {
    return new GenerationStream() {
      @Override
      public void consume(final Consumer<GenerationEvent> consumer) {
        consumer.accept(new GenerationEvent(
            "inv-1", 1, EventType.ERROR, null, null, null, null,
            null, null, "VENDOR_PRIVATE_CODE", privateDetail
        ));
        throw new AiProviderRequestException(502, "stream exception: " + privateDetail);
      }

      @Override
      public void cancel() {
        // No active network request in this test stream.
      }
    };
  }

  @Test
  void nativeResponseKeepsStatusRetryHeadersAndErrorBody() throws Exception {
    authorizeNative();
    var exchange = nativeExchange(429, false,
        "{\"error\":{\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\",\"message\":\"retry\"}}");
    when(nativeGateway.create(any(), anyString(), eq("/responses"), anyString(), any()))
        .thenReturn(Optional.of(exchange));
    mockMvc.perform(post("/v1/responses").header("Authorization", "Bearer test")
            .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"public-model\",\"input\":\"hi\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "7"))
        .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"));
    verifyNoInteractions(generationService);
    verify(exchange).close();
  }

  @Test
  void nativeSseUsesRawFramesWithoutDoubleEncoding() throws Exception {
    authorizeNative();
    String frames = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\"}}\n\n";
    var exchange = nativeExchange(200, true, frames);
    when(nativeGateway.create(any(), anyString(), eq("/responses"), anyString(), any()))
        .thenReturn(Optional.of(exchange));
    MvcResult result = mockMvc.perform(post("/v1/responses").header("Authorization", "Bearer test")
            .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"public-model\",\"input\":\"hi\",\"stream\":true}"))
        .andExpect(request().asyncStarted()).andReturn();
    mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andExpect(content().string(frames));
    verifyNoInteractions(generationService);
  }

  @Test
  void exposesNativeCompactionAndResponseLifecycleRoutes() throws Exception {
    authorizeNative();
    var compact = nativeExchange(200, false, "{\"object\":\"response.compaction\",\"output\":[]}");
    when(nativeGateway.create(any(), anyString(), eq("/responses/compact"), anyString(), any()))
        .thenReturn(Optional.of(compact));
    mockMvc.perform(post("/v1/responses/compact").header("Authorization", "Bearer test")
            .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"public-model\",\"input\":[]}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.object").value("response.compaction"));
    var items = nativeExchange(200, false, "{\"object\":\"list\",\"data\":[]}");
    when(nativeGateway.response(any(), eq("resp_1"), eq("GET"), eq("/input_items"), any(), any()))
        .thenReturn(items);
    mockMvc.perform(get("/v1/responses/resp_1/input_items?limit=2").header("Authorization", "Bearer test"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.object").value("list"));
    when(nativeGateway.response(any(), eq("resp_missing"), eq("GET"), eq(""), any(), any()))
        .thenThrow(new AiOpenAiRequestException(
            404, "resource_not_found", null, "Resource not found"));
    mockMvc.perform(get("/v1/responses/resp_missing").header("Authorization", "Bearer test"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("resource_not_found"));
  }

  private void authorizeNative() {
    when(accessService.authenticate(anyString(), anyString()))
        .thenReturn(new GatewaySession("key-1", "test", AiResourceScope.SYSTEM, null));
    when(accessService.resolveModelDefinitionId(any(), anyString())).thenReturn("definition-1");
    when(accessService.withSession(any(), any())).thenAnswer(invocation ->
        ((Supplier<?>) invocation.getArgument(1)).get());
  }

  private Exchange nativeExchange(
      final int status, final boolean stream, final String body) {
    var exchange = mock(Exchange.class);
    when(exchange.status()).thenReturn(status);
    when(exchange.streaming()).thenReturn(stream);
    when(exchange.headers()).thenReturn(Map.of("Retry-After", List.of("7")));
    doAnswer(invocation -> {
      Consumer<byte[]> consumer = invocation.getArgument(0);
      consumer.accept(body.getBytes(StandardCharsets.UTF_8));
      return null;
    }).when(exchange).consume(any());
    return exchange;
  }

  private void rejectApiKey() {
    when(accessService.authenticate(anyString(), anyString())).thenThrow(
        new AiGatewayAccessException(FailureType.AUTHENTICATION, "private-auth-detail")
    );
  }
}
