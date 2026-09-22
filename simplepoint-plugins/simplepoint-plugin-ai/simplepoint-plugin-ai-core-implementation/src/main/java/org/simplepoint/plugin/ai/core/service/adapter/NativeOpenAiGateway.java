package org.simplepoint.plugin.ai.core.service.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.simplepoint.plugin.ai.core.api.entity.AiGatewayResource;
import org.simplepoint.plugin.ai.core.api.entity.AiInvocationRecord;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.exception.AiOpenAiRequestException;
import org.simplepoint.plugin.ai.core.api.model.AiInvocationOperation;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService.GatewaySession;
import org.simplepoint.plugin.ai.core.api.service.AiNativeOpenAiGateway;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;
import org.simplepoint.plugin.ai.core.repository.AiGatewayResourceRepository;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.springframework.stereotype.Service;

/** Relays OpenAI wire objects without the lossy provider-neutral generation conversion. */
@Service
public class NativeOpenAiGateway implements AiNativeOpenAiGateway {
  private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();

  private static ScheduledThreadPoolExecutor deadlines() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
      Thread thread = new Thread(task, "ai-native-http-deadline");
      thread.setDaemon(true);
      return thread;
    });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  private static final Set<String> CREATE_PATHS = Set.of(
      "/responses", "/responses/compact", "/responses/input_tokens", "/chat/completions");
  private static final Set<String> RESPONSE_HEADERS = Set.of(
      "content-type", "x-request-id", "retry-after", "openai-processing-ms",
      "x-codex-turn-state");
  private final AiModelDefinitionRepository models;
  private final AiProviderDefinitionRepository providers;
  private final AiCredentialCipher cipher;
  private final AiScopeAccessPolicy access;
  private final AiGatewayResourceRepository resources;
  private final AiInvocationLedger ledger;
  private final ObjectMapper mapper;
  private final AiProperties properties;
  private final ProviderHttpSupport http;

  /** Creates the transport with scoped model access and durable response routing. */
  public NativeOpenAiGateway(final AiModelDefinitionRepository models,
      final AiProviderDefinitionRepository providers, final AiCredentialCipher cipher,
      final AiScopeAccessPolicy access, final AiGatewayResourceRepository resources,
      final AiInvocationLedger ledger, final ObjectMapper mapper, final AiProperties properties) {
    this.models = models;
    this.providers = providers;
    this.cipher = cipher;
    this.access = access;
    this.resources = resources;
    this.ledger = ledger;
    this.mapper = mapper;
    this.properties = properties;
    this.http = new ProviderHttpSupport(properties);
  }

  @Override
  public Optional<Exchange> create(final GatewaySession session, final String modelDefinitionId,
      final String path, final String payload, final Map<String, String> headers) {
    if (!CREATE_PATHS.contains(path)) {
      throw invalid("unsupported_endpoint", null, "Unsupported OpenAI endpoint");
    }
    Target target = target(modelDefinitionId);
    if (!supports(target.provider(), path)) {
      return Optional.empty();
    }
    if (payload.length() > ProviderHttpSupport.positive(
        properties.getGenerationMaxInputCharacters(), 1_000_000)) {
      throw invalid("request_too_large", null, "Request exceeds the configured input limit");
    }
    ObjectNode body = object(payload);
    final String publicModel = body.path("model").asText();
    validateReferences(session, target, body);
    if (body.has("stream") && !body.get("stream").isBoolean()) {
      throw invalid("invalid_request", "stream", "stream must be a boolean");
    }
    boolean suppressChatUsage = "/chat/completions".equals(path)
        && body.path("stream").asBoolean(false)
        && !body.path("stream_options").path("include_usage").asBoolean(false);
    if (suppressChatUsage) {
      if (body.hasNonNull("stream_options") && !body.get("stream_options").isObject()) {
        throw invalid("invalid_request", "stream_options", "stream_options must be an object");
      }
      ObjectNode options = body.hasNonNull("stream_options")
          ? (ObjectNode) body.get("stream_options") : body.putObject("stream_options");
      options.put("include_usage", true);
    }
    validateLimits(body);
    body.put("model", target.model().getModelId());
    return Optional.of(open(session, target, publicModel, "POST", path, body.toString(),
        headers, !path.endsWith("input_tokens"), null, suppressChatUsage));
  }

  @Override
  public Exchange response(final GatewaySession session, final String responseId,
      final String method, final String suffix, final String query,
      final Map<String, String> headers) {
    boolean allowed = ("GET".equals(method) && (suffix.isEmpty() || "/input_items".equals(suffix)))
        || ("DELETE".equals(method) && suffix.isEmpty())
        || ("POST".equals(method) && "/cancel".equals(suffix));
    if (!allowed) {
      throw invalid("unsupported_endpoint", null, "Unsupported response operation");
    }
    AiGatewayResource resource = owned(session, "response", responseId);
    Target target = target(resource.getModelDefinitionId());
    sameProvider(resource, target);
    String path = "/responses/" + responseId + suffix;
    if (query != null && !query.isBlank()) {
      if (query.indexOf('#') >= 0 || query.indexOf('\r') >= 0 || query.indexOf('\n') >= 0) {
        throw invalid("invalid_request", null, "Invalid query string");
      }
      path += "?" + query;
    }
    return open(session, target, resource.getPublicModelId(), method, path,
        "POST".equals(method) ? "{}" : null, headers, false,
        !"DELETE".equals(method) && !"/input_items".equals(suffix)
            ? ledger.find(resource.getInvocationId()) : null, false);
  }

  private Target target(final String modelId) {
    AiModelDefinition model = models.findActiveById(modelId).orElseThrow(() ->
        invalid("model_not_found", "model", "Model is not available"));
    if (!access.canUseResource(model.getScopeType(), model.getTenantId())
        || !Boolean.TRUE.equals(model.getEnabled()) || !Boolean.TRUE.equals(model.getAvailable())
        || (model.getModelType() != AiModelType.LLM && model.getModelType() != AiModelType.MULTIMODAL)) {
      throw invalid("model_not_found", "model", "Model is not available");
    }
    AiProviderDefinition provider = providers.findActiveById(model.getProviderId()).orElseThrow(() ->
        invalid("model_not_found", "model", "Provider is not available"));
    if (!access.canUseResource(provider.getScopeType(), provider.getTenantId())
        || !Boolean.TRUE.equals(provider.getEnabled())) {
      throw invalid("model_not_found", "model", "Provider is not available");
    }
    return new Target(model, provider);
  }

  private boolean supports(final AiProviderDefinition provider, final String path) {
    return provider.getProviderType() == AiProviderType.OPENAI
        || (provider.getProviderType() == AiProviderType.OPENAI_COMPATIBLE
            && "/chat/completions".equals(path));
  }

  private void validateReferences(final GatewaySession session, final Target target,
      final ObjectNode body) {
    JsonNode previous = body.get("previous_response_id");
    if (previous != null && !previous.isNull()) {
      sameProvider(owned(session, "response", previous.asText()), target);
    }
    if (body.hasNonNull("conversation")) {
      throw invalid("unsupported_parameter", "conversation",
          "Use previous_response_id or explicit input history; Conversations are not exposed by this gateway");
    }
    JsonNode input = body.path("input");
    if (input.isArray()) {
      for (JsonNode item : input) {
        if ("item_reference".equals(item.path("type").asText())) {
          sameProvider(owned(session, "item", item.path("id").asText()), target);
        }
        JsonNode content = item.path("content");
        if (content.isArray()) {
          for (JsonNode part : content) {
            if (part.hasNonNull("file_id")) {
              throw invalid("unsupported_parameter", "input.content.file_id",
                  "Use inline file_data, file_url or image_url; upstream file IDs are not shared between API keys");
            }
          }
        }
      }
    }
    for (JsonNode tool : body.path("tools")) {
      if (tool.hasNonNull("vector_store_ids") || tool.path("container").isTextual()
          || !tool.path("container").path("file_ids").isEmpty()) {
        throw invalid("unsupported_parameter", "tools",
            "Existing upstream vector stores and containers are not shared between API keys");
      }
    }
  }

  private void validateLimits(final ObjectNode body) {
    JsonNode messages = body.has("messages") ? body.get("messages") : body.path("input");
    if (messages.isArray() && messages.size() > ProviderHttpSupport.positive(
        properties.getGenerationMaxMessages(), 1000)) {
      throw invalid("request_too_large", "input", "Too many input items");
    }
    int limit = ProviderHttpSupport.positive(properties.getGenerationMaxTools(), 256);
    if (toolCount(body.path("tools")) > limit) {
      throw invalid("request_too_large", "tools", "Too many tools");
    }
    for (String field : List.of("max_output_tokens", "max_completion_tokens", "max_tokens")) {
      JsonNode value = body.get(field);
      if (value != null && !value.isNull() && (!value.isIntegralNumber()
          || !value.canConvertToInt() || value.intValue() <= 0 || value.intValue()
          > ProviderHttpSupport.positive(properties.getGenerationMaxOutputTokens(), 32_768))) {
        throw invalid("invalid_request", field, "Token limit exceeds the configured range");
      }
    }
  }

  private int toolCount(final JsonNode tools) {
    int count = 0;
    for (JsonNode tool : tools) {
      count += "namespace".equals(tool.path("type").asText()) ? toolCount(tool.path("tools")) : 1;
    }
    return count;
  }

  private AiGatewayResource owned(final GatewaySession session, final String type, final String id) {
    validateId(id);
    return resources.findById(bindingId(session.apiKeyId(), type, id))
        .filter(resource -> session.apiKeyId().equals(resource.getApiKeyId()))
        .orElseThrow(() -> new AiOpenAiRequestException(404, "resource_not_found", null,
            "Resource does not exist or is not owned by this API key"));
  }

  private void sameProvider(final AiGatewayResource resource, final Target target) {
    if (!Objects.equals(resource.getProviderDefinitionId(), target.provider().getId())) {
      throw invalid("invalid_request", "previous_response_id",
          "Resource belongs to a different provider");
    }
  }

  private void bind(final GatewaySession session, final Target target, final String publicModel,
      final String type, final String id, final AiInvocationRecord record) {
    if (id == null || id.isBlank()) {
      return;
    }
    validateId(id);
    String key = bindingId(session.apiKeyId(), type, id);
    Optional<AiGatewayResource> existing = resources.findById(key);
    if (existing.isPresent()) {
      sameProvider(existing.get(), target);
      return;
    }
    AiGatewayResource binding = new AiGatewayResource();
    binding.setId(key);
    binding.setApiKeyId(session.apiKeyId());
    binding.setProviderDefinitionId(target.provider().getId());
    binding.setModelDefinitionId(target.model().getId());
    binding.setPublicModelId(publicModel);
    binding.setResourceType(type);
    binding.setUpstreamId(id);
    binding.setCreatedAt(Instant.now());
    binding.setInvocationId(record == null ? null : record.getId());
    // Commit before exposing the identifier so a subsequent request can resolve it on any replica.
    resources.saveAndFlush(binding);
  }

  private Exchange open(final GatewaySession session, final Target target, final String publicModel,
      final String method, final String path, final String body, final Map<String, String> headers,
      final boolean billable, final AiInvocationRecord existingRecord,
      final boolean suppressChatUsage) {
    String credential = cipher.decrypt(target.provider().getCredentialCiphertext());
    if ((credential == null || credential.isBlank())
        && target.provider().getProviderType() != AiProviderType.OPENAI_COMPATIBLE) {
      throw new IllegalStateException("Provider API key is not configured");
    }
    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(ProviderHttpSupport.endpoint(target.provider().getBaseUrl(), path))
        .timeout(ProviderHttpSupport.timeout(
            ProviderHttpSupport.positive(properties.getRequestTimeoutSeconds(), 30)))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    if (credential != null && !credential.isBlank()) {
      request.header("Authorization", "Bearer " + credential);
    }
    headers.forEach((name, value) -> {
      String lower = name.toLowerCase(Locale.ROOT);
      if (Set.of("openai-beta", "x-client-request-id", "x-codex-turn-state").contains(lower)) {
        request.header(name, value);
      } else if (Set.of("idempotency-key", "session_id", "conversation_id").contains(lower)) {
        request.header(name, bindingId(session.apiKeyId(), lower, value));
      }
    });
    AiInvocationRecord record = billable ? ledger.start(UUID.randomUUID().toString(),
        target.provider(), target.model(), AiInvocationOperation.GENERATION,
        body != null && object(body).path("stream").asBoolean(false)) : existingRecord;
    AiStreamCancellation cancellation = new AiStreamCancellation();
    long started = System.nanoTime();
    try {
      var response = http.open(request.build(),
          Boolean.TRUE.equals(target.provider().getAllowPrivateNetwork()), cancellation);
      return new NativeExchange(session, target, publicModel, response, cancellation,
          record, started, credential, path.startsWith("/responses"), billable, suppressChatUsage);
    } catch (RuntimeException ex) {
      cancellation.cancel();
      if (billable) {
        ledger.failure(record, ex);
      }
      throw ex;
    }
  }

  private final class NativeExchange implements Exchange {
    private final GatewaySession session;
    private final Target target;
    private final String publicModel;
    private final HttpResponse<InputStream> response;
    private final AiStreamCancellation cancellation;
    private final AiInvocationRecord record;
    private final long started;
    private final String credential;
    private final boolean responses;
    private final boolean billable;
    private final boolean suppressChatUsage;
    private final ScheduledFuture<?> deadline;
    private boolean pending;
    private boolean responseLifecycle;
    private boolean responseCancelled;
    private final AtomicBoolean consumed = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private boolean terminal;
    private boolean failed;
    private TokenUsage usage;
    private String responseId;

    private NativeExchange(final GatewaySession session, final Target target, final String publicModel,
        final HttpResponse<InputStream> response, final AiStreamCancellation cancellation,
        final AiInvocationRecord record, final long started, final String credential,
        final boolean responses, final boolean billable, final boolean suppressChatUsage) {
      this.session = session;
      this.target = target;
      this.publicModel = publicModel;
      this.response = response;
      this.cancellation = cancellation;
      this.record = record;
      this.started = started;
      this.credential = credential;
      this.responses = responses;
      this.billable = billable;
      this.suppressChatUsage = suppressChatUsage;
      long timeout = streaming()
          ? ProviderHttpSupport.positive(properties.getStreamingTimeoutMs(), 300_000L)
          : ProviderHttpSupport.positive(properties.getRequestTimeoutSeconds(), 30) * 1000L;
      this.deadline = DEADLINES.schedule(this::close, timeout, TimeUnit.MILLISECONDS);
    }

    @Override
    public int status() {
      return response.statusCode();
    }

    @Override
    public Map<String, List<String>> headers() {
      Map<String, List<String>> result = new LinkedHashMap<>();
      response.headers().map().forEach((name, values) -> {
        String lower = name.toLowerCase(Locale.ROOT);
        if (RESPONSE_HEADERS.contains(lower) || lower.startsWith("x-ratelimit-")) {
          result.put(name, values);
        }
      });
      return result;
    }

    @Override
    public boolean streaming() {
      return status() >= 200 && status() < 300
          && response.headers().firstValue("content-type").orElse("")
              .toLowerCase(Locale.ROOT).startsWith("text/event-stream");
    }

    @Override
    public void consume(final Consumer<byte[]> consumer) {
      if (!consumed.compareAndSet(false, true)) {
        throw new IllegalStateException("Response has already been consumed");
      }
      try (InputStream input = response.body()) {
        cancellation.throwIfCancelled();
        if (streaming()) {
          StringBuilder frame = new StringBuilder();
          StringBuilder data = new StringBuilder();
          http.readLines(input, line -> {
            cancellation.throwIfCancelled();
            frame.append(line).append('\n');
            if (frame.length() > ProviderHttpSupport.positive(
                properties.getProviderMaxResponseBytes(), 10L * 1024L * 1024L)) {
              throw new IllegalStateException("SSE event exceeds the configured size limit");
            }
            if (line.startsWith("data:")) {
              if (!data.isEmpty()) {
                data.append('\n');
              }
              String value = line.substring(5);
              data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
            if (line.isEmpty()) {
              dispatch(frame, data, consumer);
            }
          });
          if (!frame.isEmpty()) {
            throw new IllegalStateException("Upstream SSE ended inside an event");
          }
          if (!terminal) {
            throw new IllegalStateException("Upstream SSE ended before a terminal event");
          }
        } else {
          String payload = new String(http.readBody(input), StandardCharsets.UTF_8);
          if (status() >= 200 && status() < 300) {
            if (!payload.isBlank()) {
              observe(object(payload));
            }
          } else {
            failed = true;
            try {
              object(payload);
            } catch (RuntimeException ex) {
              payload = "{\"error\":{\"type\":\"api_error\",\"code\":\"upstream_error\","
                  + "\"param\":null,\"message\":\"Upstream returned a non-JSON error\"}}";
            }
          }
          consumer.accept(redact(payload).getBytes(StandardCharsets.UTF_8));
        }
        cancellation.throwIfCancelled();
        if (!finished.compareAndSet(false, true)) {
          throw new CancellationException("Response was closed before completion");
        }
        if (responseCancelled) {
          ledger.cancelled(record);
        } else if (failed && (billable || responseLifecycle)) {
          ledger.failure(record, new IllegalStateException("Upstream OpenAI request failed"));
        } else if (!failed && !pending && (billable || responseLifecycle)) {
          long duration = !billable && record != null && record.getStartedAt() != null
              ? Math.max(0, Instant.now().toEpochMilli() - record.getStartedAt().toEpochMilli())
              : (System.nanoTime() - started) / 1_000_000;
          ledger.success(record, usage, duration, responseId);
        }
        // Background responses remain RUNNING until a later retrieval observes a terminal status.
      } catch (IOException ex) {
        fail(ex);
        throw new IllegalStateException("Upstream response interrupted", ex);
      } catch (RuntimeException ex) {
        fail(ex);
        throw ex;
      } finally {
        deadline.cancel(false);
        cancellation.releaseResponseBody(response.body());
      }
    }

    private void dispatch(final StringBuilder frame, final StringBuilder data,
        final Consumer<byte[]> consumer) {
      boolean suppress = false;
      if (!data.isEmpty()) {
        if ("[DONE]".contentEquals(data)) {
          if (!responses) {
            terminal = true;
          }
        } else {
          JsonNode value = object(data.toString());
          observe(value);
          suppress = suppressChatUsage && value.path("choices").isArray()
              && value.path("choices").isEmpty() && value.hasNonNull("usage");
        }
      }
      if (!suppress) {
        consumer.accept(redact(frame.toString()).getBytes(StandardCharsets.UTF_8));
      }
      frame.setLength(0);
      data.setLength(0);
    }

    private void observe(final JsonNode event) {
      String type = event.path("type").asText();
      if (Set.of("response.completed", "response.incomplete", "response.failed",
          "response.cancelled", "error").contains(type)) {
        terminal = true;
      }
      if (Set.of("response.failed", "response.cancelled", "error").contains(type)) {
        failed = true;
      }
      JsonNode value = event.has("response") ? event.get("response") : event;
      if (value.hasNonNull("id")) {
        responseId = value.get("id").asText();
      }
      if (responses && ("response".equals(value.path("object").asText())
          || event.has("response"))) {
        responseLifecycle = true;
        String status = value.path("status").asText();
        pending = "queued".equals(status) || "in_progress".equals(status);
        responseCancelled = "cancelled".equals(status);
        bind(session, target, publicModel, "response", value.path("id").asText(null), record);
        for (JsonNode item : value.path("output")) {
          bind(session, target, publicModel, "item", item.path("id").asText(null), record);
        }
      }
      if (responses && "list".equals(value.path("object").asText())) {
        for (JsonNode item : value.path("data")) {
          bind(session, target, publicModel, "item", item.path("id").asText(null), record);
        }
      }
      if (responses && "response.output_item.done".equals(type)) {
        bind(session, target, publicModel, "item", event.path("item").path("id").asText(null), record);
      }
      JsonNode counts = value.path("usage");
      if (counts.isObject()) {
        boolean chat = counts.has("prompt_tokens");
        usage = new TokenUsage(integer(counts, chat ? "prompt_tokens" : "input_tokens"),
            integer(counts, chat ? "completion_tokens" : "output_tokens"),
            integer(counts, "total_tokens"), integer(counts.path(
                chat ? "prompt_tokens_details" : "input_tokens_details"), "cached_tokens"));
      }
      if ("failed".equals(value.path("status").asText()) || value.hasNonNull("error")) {
        failed = true;
      }
    }

    private String redact(final String value) {
      return credential == null || credential.isBlank() ? value : value.replace(credential, "[REDACTED]");
    }

    private void fail(final Exception error) {
      if (finished.compareAndSet(false, true) && billable) {
        if (cancellation.isCancelled() || error instanceof CancellationException) {
          ledger.cancelled(record);
        } else {
          ledger.failure(record, new IllegalStateException("OpenAI transport failed"));
        }
      }
    }

    @Override
    public void close() {
      cancellation.cancel();
      if (deadline != null) {
        deadline.cancel(false);
      }
      if (finished.compareAndSet(false, true) && billable) {
        ledger.cancelled(record);
      }
    }
  }

  private ObjectNode object(final String json) {
    try {
      JsonNode node = mapper.readTree(json);
      if (node instanceof ObjectNode object) {
        return object;
      }
    } catch (IOException ex) {
      throw invalid("invalid_json", null, "Expected a JSON object");
    }
    throw invalid("invalid_json", null, "Expected a JSON object");
  }

  private static Integer integer(final JsonNode node, final String field) {
    return node.path(field).canConvertToInt() ? node.path(field).intValue() : null;
  }

  private static void validateId(final String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,256}")) {
      throw invalid("invalid_request", null, "Invalid resource identifier");
    }
  }

  static String bindingId(final String owner, final String type, final String id) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
          (owner + "\0" + type + "\0" + id).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static AiOpenAiRequestException invalid(final String code, final String param,
      final String message) {
    return new AiOpenAiRequestException(400, code, param, message);
  }

  private record Target(AiModelDefinition model, AiProviderDefinition provider) {
  }
}
