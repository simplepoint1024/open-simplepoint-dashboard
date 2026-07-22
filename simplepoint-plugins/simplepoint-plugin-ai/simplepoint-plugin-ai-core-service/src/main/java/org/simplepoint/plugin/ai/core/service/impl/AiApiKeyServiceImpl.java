package org.simplepoint.plugin.ai.core.service.impl;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.ai.core.api.entity.AiApiKey;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiApiKeyRepository;
import org.simplepoint.plugin.ai.core.api.service.AiApiKeyService;
import org.simplepoint.plugin.ai.core.service.security.AiApiKeyHasher;
import org.simplepoint.plugin.ai.core.service.security.AiApiKeyHasher.IssuedSecret;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Scope-safe API key management with one-time secret disclosure. */
@Service
public class AiApiKeyServiceImpl
    extends BaseServiceImpl<AiApiKeyRepository, AiApiKey, String>
    implements AiApiKeyService {

  private final AiApiKeyRepository repository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiApiKeyHasher keyHasher;

  /** Creates the scope-aware key management service. */
  public AiApiKeyServiceImpl(
      final AiApiKeyRepository repository,
      final DetailsProviderService detailsProviderService,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiApiKeyHasher keyHasher
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.keyHasher = keyHasher;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  public <S extends AiApiKey> Page<S> limit(
      final Map<String, String> attributes,
      final Pageable pageable
  ) {
    Map<String, String> scoped = new LinkedHashMap<>();
    if (attributes != null) {
      scoped.putAll(attributes);
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    scoped.put("scopeType", scope.scopeType().name());
    scoped.put("tenantId", scope.tenantId() == null ? "is:null" : scope.tenantId());
    scoped.put("deletedAt", "is:null");
    normalizeLike(scoped, "name");
    return super.limit(scoped, pageable);
  }

  @Override
  public Optional<AiApiKey> findById(final String id) {
    Optional<AiApiKey> key = repository.findActiveById(requireId(id));
    key.ifPresent(this::assertCanRead);
    return key;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends AiApiKey> S create(final S entity) {
    if (entity == null) {
      throw new IllegalArgumentException("模型 API Key 配置不能为空");
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    entity.setScopeType(scope.scopeType());
    entity.setTenantId(scope.tenantId());
    normalizeEditable(entity, null);
    IssuedSecret issued = keyHasher.issue();
    entity.setKeyPrefix(issued.prefix());
    entity.setSecretHash(issued.hash());
    entity.setUsageCount(0L);
    entity.setLastUsedAt(null);
    entity.setRevokedAt(null);
    entity.setIssuedKey(null);
    S saved = super.create(entity);
    saved.setIssuedKey(issued.rawKey());
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends AiApiKey> AiApiKey modifyById(final S entity) {
    AiApiKey current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("模型 API Key 不存在"));
    scopeAccessPolicy.assertCanManageOwnedResource(current.getScopeType(), current.getTenantId());
    entity.setScopeType(current.getScopeType());
    entity.setTenantId(current.getTenantId());
    entity.setKeyPrefix(current.getKeyPrefix());
    entity.setSecretHash(current.getSecretHash());
    entity.setUsageCount(current.getUsageCount());
    entity.setLastUsedAt(current.getLastUsedAt());
    entity.setRevokedAt(current.getRevokedAt());
    entity.setIssuedKey(null);
    normalizeEditable(entity, current);
    return super.modifyById(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiApiKey rotate(final String id) {
    AiApiKey current = repository.findActiveById(requireId(id))
        .orElseThrow(() -> new IllegalArgumentException("模型 API Key 不存在"));
    scopeAccessPolicy.assertCanManageOwnedResource(current.getScopeType(), current.getTenantId());
    IssuedSecret issued = keyHasher.issue();
    current.setKeyPrefix(issued.prefix());
    current.setSecretHash(issued.hash());
    current.setLastUsedAt(null);
    current.setUsageCount(0L);
    current.setRevokedAt(null);
    current.setEnabled(Boolean.TRUE);
    AiApiKey saved = repository.save(current);
    saved.setIssuedKey(issued.rawKey());
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    List<AiApiKey> keys = ids.stream()
        .map(repository::findActiveById)
        .map(key -> key.orElseThrow(() -> new IllegalArgumentException("模型 API Key 不存在或无权访问")))
        .toList();
    keys.forEach(key -> scopeAccessPolicy.assertCanManageOwnedResource(
        key.getScopeType(), key.getTenantId()));
    Instant revokedAt = Instant.now();
    keys.forEach(key -> {
      key.setEnabled(Boolean.FALSE);
      key.setRevokedAt(revokedAt);
      repository.save(key);
    });
    super.removeByIds(ids);
  }

  @Override
  protected Set<Map<String, Object>> getButtonDeclarationsSchema(final Class<AiApiKey> domainClass) {
    String authorityPrefix = scopeAccessPolicy.currentManagementScope().scopeType() == AiResourceScope.SYSTEM
        ? "ai.system.api-keys." : "ai.api-keys.";
    return super.getButtonDeclarationsSchema(domainClass).stream()
        .filter(button -> String.valueOf(button.get("authority")).startsWith(authorityPrefix))
        .collect(Collectors.toSet());
  }

  private void normalizeEditable(final AiApiKey entity, final AiApiKey current) {
    entity.setName(requireText(entity.getName(), "API Key 名称不能为空"));
    entity.setDescription(trimToNull(entity.getDescription()));
    if (entity.getName().length() > 128) {
      throw new IllegalArgumentException("API Key 名称不能超过 128 个字符");
    }
    if (entity.getDescription() != null && entity.getDescription().length() > 512) {
      throw new IllegalArgumentException("API Key 描述不能超过 512 个字符");
    }
    if (entity.getExpiresAt() != null
        && !entity.getExpiresAt().isAfter(Instant.now())
        && (current == null || !Objects.equals(entity.getExpiresAt(), current.getExpiresAt()))) {
      throw new IllegalArgumentException("API Key 过期时间必须晚于当前时间");
    }
    if (entity.getRateLimitPerMinute() != null
        && (entity.getRateLimitPerMinute() < 1 || entity.getRateLimitPerMinute() > 100_000)) {
      throw new IllegalArgumentException("每分钟请求上限必须在 1 到 100000 之间");
    }
    entity.setEnabled(entity.getEnabled() == null ? Boolean.TRUE : entity.getEnabled());
    if (repository.existsActiveByNameAndScope(
        entity.getName(), entity.getScopeType(), entity.getTenantId(),
        current == null ? null : current.getId())) {
      throw new IllegalArgumentException("当前作用域已存在同名 API Key");
    }
  }

  private void assertCanRead(final AiApiKey key) {
    scopeAccessPolicy.assertCanReadManagedResource(key.getScopeType(), key.getTenantId());
  }

  private static String requireEntityId(final AiApiKey entity) {
    return entity == null ? requireId(null) : requireId(entity.getId());
  }

  private static String requireId(final String id) {
    return requireText(id, "模型 API Key ID 不能为空");
  }

  private static String requireText(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static void normalizeLike(final Map<String, String> attributes, final String field) {
    String value = attributes.get(field);
    if (value != null && !value.isBlank() && !value.contains(":")) {
      attributes.put(field, "like:" + value.trim());
    }
  }
}
