package org.simplepoint.plugin.ai.core.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.simplepoint.core.AuthorizationActorRole;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.ai.core.api.entity.AiApiKey;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.exception.AiGatewayAccessException;
import org.simplepoint.plugin.ai.core.api.exception.AiGatewayAccessException.FailureType;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.repository.AiApiKeyRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService;
import org.simplepoint.plugin.ai.core.service.security.AiApiKeyHasher;
import org.springframework.stereotype.Service;

/** Default API key authenticator and public model catalog resolver. */
@Service
public class AiGatewayAccessServiceImpl implements AiGatewayAccessService {

  private static final String TENANT_ID_ATTRIBUTE = "X-Tenant-Id";

  private final AiApiKeyRepository apiKeyRepository;

  private final AiModelDefinitionRepository modelRepository;

  private final AiApiKeyHasher keyHasher;

  private final AiProperties properties;

  private final ConcurrentHashMap<String, MinuteBucket> rateLimits = new ConcurrentHashMap<>();

  /** Creates the gateway authenticator and model catalog resolver. */
  public AiGatewayAccessServiceImpl(
      final AiApiKeyRepository apiKeyRepository,
      final AiModelDefinitionRepository modelRepository,
      final AiApiKeyHasher keyHasher,
      final AiProperties properties
  ) {
    this.apiKeyRepository = apiKeyRepository;
    this.modelRepository = modelRepository;
    this.keyHasher = keyHasher;
    this.properties = properties;
  }

  @Override
  public GatewaySession authenticate(final String rawApiKey, final String remoteAddress) {
    String prefix = keyHasher.prefix(rawApiKey);
    AiApiKey key = prefix == null ? null : apiKeyRepository.findActiveByPrefix(prefix).orElse(null);
    if (key == null || !keyHasher.matches(rawApiKey, key.getSecretHash())) {
      throw failure(FailureType.AUTHENTICATION, "无效的 API Key");
    }
    Instant now = Instant.now();
    if (!Boolean.TRUE.equals(key.getEnabled()) || key.getRevokedAt() != null) {
      throw failure(FailureType.AUTHENTICATION, "API Key 已禁用或已吊销");
    }
    if (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(now)) {
      throw failure(FailureType.AUTHENTICATION, "API Key 已过期");
    }
    enforceRateLimit(key, now);
    apiKeyRepository.recordUsage(key.getId(), now);
    return new GatewaySession(
        key.getId(), key.getName(), key.getScopeType(), normalize(key.getTenantId())
    );
  }

  @Override
  public List<GatewayModel> availableModels(final GatewaySession session) {
    requireSession(session);
    List<AiModelDefinition> models = session.scopeType() == AiResourceScope.SYSTEM
        ? modelRepository.findAllAvailableSystemModels()
        : modelRepository.findAllAvailableForTenant(requireTenantId(session));
    List<AiModelDefinition> generationModels = models.stream()
        .filter(model -> model.getModelType() == AiModelType.LLM
            || model.getModelType() == AiModelType.MULTIMODAL)
        .toList();
    Map<String, Integer> modelIdCounts = new LinkedHashMap<>();
    generationModels.forEach(model -> modelIdCounts.merge(model.getModelId(), 1, Integer::sum));
    List<GatewayModel> result = new ArrayList<>(generationModels.size());
    for (AiModelDefinition model : generationModels) {
      String externalId = modelIdCounts.getOrDefault(model.getModelId(), 0) == 1
          ? model.getModelId() : model.getId();
      long createdAt = model.getCreatedAt() == null ? 0L : model.getCreatedAt().getEpochSecond();
      result.add(new GatewayModel(
          externalId,
          model.getId(),
          normalize(model.getDisplayName()) == null ? model.getModelId() : model.getDisplayName().trim(),
          createdAt
      ));
    }
    return List.copyOf(result);
  }

  @Override
  public String resolveModelDefinitionId(
      final GatewaySession session,
      final String externalModelId
  ) {
    String requested = normalize(externalModelId);
    if (requested == null) {
      throw new IllegalArgumentException("model 不能为空");
    }
    List<GatewayModel> models = availableModels(session);
    return models.stream()
        .filter(model -> requested.equals(model.id()) || requested.equals(model.modelDefinitionId()))
        .map(GatewayModel::modelDefinitionId)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("模型不存在或当前 API Key 无权访问: " + requested));
  }

  @Override
  public <T> T withSession(final GatewaySession session, final Supplier<T> operation) {
    requireSession(session);
    if (operation == null) {
      throw new IllegalArgumentException("网关调用不能为空");
    }
    AuthorizationContext previous = AuthorizationContextHolder.getContext();
    AuthorizationContext context = new AuthorizationContext();
    context.setContextId("api-key:" + session.apiKeyId());
    context.setUserId("api-key:" + session.apiKeyId());
    context.setIsAdministrator(Boolean.FALSE);
    context.setRoles(List.of());
    context.setResources(List.of("ai.inference.invoke"));
    context.setScopeType(session.scopeType() == AiResourceScope.SYSTEM
        ? AuthorizationScopeType.PLATFORM : AuthorizationScopeType.TENANT);
    context.setActorRole(session.scopeType() == AiResourceScope.SYSTEM
        ? AuthorizationActorRole.PLATFORM_ADMIN : AuthorizationActorRole.TENANT_MEMBER);
    context.setAttributes(session.scopeType() == AiResourceScope.TENANT
        ? Map.of(TENANT_ID_ATTRIBUTE, requireTenantId(session)) : Map.of());
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    try {
      return operation.get();
    } finally {
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
      if (previous != null) {
        RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, previous);
      }
    }
  }

  private void enforceRateLimit(final AiApiKey key, final Instant now) {
    int configured = key.getRateLimitPerMinute() == null
        ? positive(properties.getApiKeyDefaultRateLimitPerMinute(), 60)
        : key.getRateLimitPerMinute();
    long minute = now.getEpochSecond() / 60;
    MinuteBucket bucket = rateLimits.compute(key.getId(), (ignored, current) ->
        current == null || current.minute != minute ? new MinuteBucket(minute) : current);
    if (bucket.requests.incrementAndGet() > configured) {
      throw failure(FailureType.RATE_LIMIT, "API Key 已超过每分钟请求上限");
    }
    if (rateLimits.size() > 10_000) {
      rateLimits.entrySet().removeIf(entry -> entry.getValue().minute < minute - 1);
    }
  }

  private static GatewaySession requireSession(final GatewaySession session) {
    if (session == null || normalize(session.apiKeyId()) == null || session.scopeType() == null) {
      throw failure(FailureType.AUTHENTICATION, "缺少有效的 API Key 会话");
    }
    if (session.scopeType() == AiResourceScope.TENANT && normalize(session.tenantId()) == null) {
      throw failure(FailureType.PERMISSION, "API Key 缺少租户作用域");
    }
    return session;
  }

  private static String requireTenantId(final GatewaySession session) {
    String tenantId = normalize(session.tenantId());
    if (tenantId == null) {
      throw failure(FailureType.PERMISSION, "API Key 缺少租户作用域");
    }
    return tenantId;
  }

  private static AiGatewayAccessException failure(
      final FailureType type,
      final String message
  ) {
    return new AiGatewayAccessException(type, message);
  }

  private static int positive(final Integer value, final int fallback) {
    return value != null && value > 0 ? value : fallback;
  }

  private static String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static final class MinuteBucket {

    private final long minute;

    private final AtomicInteger requests = new AtomicInteger();

    private MinuteBucket(final long minute) {
      this.minute = minute;
    }
  }
}
