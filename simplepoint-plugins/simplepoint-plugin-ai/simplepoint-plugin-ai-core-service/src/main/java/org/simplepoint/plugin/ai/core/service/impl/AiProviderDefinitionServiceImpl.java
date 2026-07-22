package org.simplepoint.plugin.ai.core.service.impl;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.service.AiProviderDefinitionService;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider configuration service implementation.
 */
@Service
public class AiProviderDefinitionServiceImpl
    extends BaseServiceImpl<AiProviderDefinitionRepository, AiProviderDefinition, String>
    implements AiProviderDefinitionService {

  private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

  private static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1";

  private final AiProviderDefinitionRepository repository;

  private final AiCredentialCipher credentialCipher;

  private final AiModelDefinitionRepository modelRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  /**
   * Creates the provider service.
   *
   * @param repository             provider repository
   * @param detailsProviderService details provider
   * @param credentialCipher       credential encryption service
   * @param modelRepository        model repository
   * @param scopeAccessPolicy      AI resource scope policy
   */
  public AiProviderDefinitionServiceImpl(
      final AiProviderDefinitionRepository repository,
      final DetailsProviderService detailsProviderService,
      final AiCredentialCipher credentialCipher,
      final AiModelDefinitionRepository modelRepository,
      final AiScopeAccessPolicy scopeAccessPolicy
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.credentialCipher = credentialCipher;
    this.modelRepository = modelRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<AiProviderDefinition> findActiveById(final String id) {
    Optional<AiProviderDefinition> provider = repository.findActiveById(requireId(id));
    provider.ifPresent(this::assertCanRead);
    return provider.map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<AiProviderDefinition> findActiveByCode(final String code) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    return repository.findActiveByCodeAndScope(
        normalizeCode(code),
        scope.scopeType(),
        scope.tenantId()
    ).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public List<AiProviderDefinition> listAutoSyncProviders() {
    return repository.findAllAutoSyncEnabled();
  }

  /** {@inheritDoc} */
  @Override
  public <S extends AiProviderDefinition> Page<S> limit(
      final Map<String, String> attributes,
      final Pageable pageable
  ) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    if (scope.tenantId() == null) {
      normalized.put("tenantId", "is:null");
    } else {
      normalized.put("tenantId", scope.tenantId());
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "code");
    Page<S> page = super.limit(normalized, pageable);
    page.getContent().forEach(this::decorate);
    return page;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends AiProviderDefinition> S create(final S entity) {
    ScopeAssignment scope = scopeAccessPolicy.requireCreateScope();
    entity.setScopeType(scope.scopeType());
    entity.setTenantId(scope.tenantId());
    normalizeAndValidate(entity, null);
    String desiredCiphertext = null;
    if (entity.getApiKey() != null && !entity.getApiKey().isBlank()) {
      desiredCiphertext = credentialCipher.encrypt(entity.getApiKey().trim());
      entity.setCredentialCiphertext(desiredCiphertext);
    }
    entity.setApiKey(null);
    normalizePrivateNetworkAccess(entity, scope.scopeType(), null);
    entity.setEnabled(entity.getEnabled() == null ? Boolean.TRUE : entity.getEnabled());
    entity.setAutoSyncEnabled(
        entity.getAutoSyncEnabled() == null ? Boolean.TRUE : entity.getAutoSyncEnabled()
    );
    entity.setLastStatus(null);
    entity.setLastMessage(null);
    entity.setLastTestedAt(null);
    entity.setLastSyncedAt(null);
    S saved = super.create(entity);
    if (desiredCiphertext != null
        && !java.util.Objects.equals(desiredCiphertext, saved.getCredentialCiphertext())) {
      saved.setCredentialCiphertext(desiredCiphertext);
      repository.save(saved);
    }
    decorate(saved);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends AiProviderDefinition> AiProviderDefinition modifyById(final S entity) {
    AiProviderDefinition current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("AI 供应商不存在: " + entity.getId()));
    scopeAccessPolicy.assertCanWriteResource(current.getScopeType(), current.getTenantId());
    entity.setScopeType(AiScopeAccessPolicy.effectiveScope(current.getScopeType()));
    entity.setTenantId(current.getTenantId());
    normalizeAndValidate(entity, current.getId());
    normalizePrivateNetworkAccess(entity, entity.getScopeType(), current);
    String desiredCiphertext = current.getCredentialCiphertext();
    if (entity.getApiKey() != null && !entity.getApiKey().isBlank()) {
      desiredCiphertext = credentialCipher.encrypt(entity.getApiKey().trim());
    }
    entity.setApiKey(null);
    entity.setCredentialCiphertext(current.getCredentialCiphertext());
    entity.setLastStatus(current.getLastStatus());
    entity.setLastMessage(current.getLastMessage());
    entity.setLastTestedAt(current.getLastTestedAt());
    entity.setLastSyncedAt(current.getLastSyncedAt());
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    if (entity.getAutoSyncEnabled() == null) {
      entity.setAutoSyncEnabled(current.getAutoSyncEnabled());
    }
    AiProviderDefinition updated = (AiProviderDefinition) super.modifyById(entity);
    if (!java.util.Objects.equals(desiredCiphertext, updated.getCredentialCiphertext())) {
      updated.setCredentialCiphertext(desiredCiphertext);
      updated = repository.save(updated);
    }
    decorate(updated);
    return updated;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    List<AiProviderDefinition> providers = ids.stream()
        .map(repository::findActiveById)
        .map(provider -> provider.orElseThrow(() ->
            new IllegalArgumentException("AI 供应商不存在或无权访问")))
        .toList();
    providers.forEach(provider -> scopeAccessPolicy.assertCanWriteResource(
        provider.getScopeType(),
        provider.getTenantId()
    ));
    Set<String> modelIds = providers.stream()
        .flatMap(provider -> modelRepository.findAllActiveByProviderId(provider.getId()).stream())
        .map(org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition::getId)
        .collect(java.util.stream.Collectors.toSet());
    if (!modelIds.isEmpty()) {
      modelRepository.deleteByIds(modelIds);
    }
    super.removeByIds(ids);
  }

  private void normalizeAndValidate(
      final AiProviderDefinition entity,
      final String currentId
  ) {
    if (entity == null) {
      throw new IllegalArgumentException("AI 供应商配置不能为空");
    }
    entity.setName(requireValue(entity.getName(), "供应商名称不能为空"));
    entity.setCode(normalizeCode(entity.getCode()));
    if (entity.getProviderType() == null) {
      throw new IllegalArgumentException("供应商协议不能为空");
    }
    if (entity.getBaseUrl() == null || entity.getBaseUrl().isBlank()) {
      entity.setBaseUrl(defaultBaseUrl(entity.getProviderType()));
    }
    entity.setBaseUrl(validateBaseUrl(entity.getBaseUrl()));
    entity.setOrganizationId(trimToNull(entity.getOrganizationId()));
    entity.setProjectId(trimToNull(entity.getProjectId()));
    entity.setApiVersion(trimToNull(entity.getApiVersion()));
    entity.setDescription(trimToNull(entity.getDescription()));
    AiResourceScope scopeType = AiScopeAccessPolicy.effectiveScope(entity.getScopeType());
    repository.findActiveByCodeAndScope(
        entity.getCode(),
        scopeType,
        entity.getTenantId()
    ).ifPresent(existing -> {
      if (!existing.getId().equals(currentId)) {
        throw new IllegalArgumentException("供应商编码已存在: " + entity.getCode());
      }
    });
  }

  private AiProviderDefinition decorate(final AiProviderDefinition entity) {
    entity.setScopeType(AiScopeAccessPolicy.effectiveScope(entity.getScopeType()));
    entity.setHasApiKey(
        entity.getCredentialCiphertext() != null && !entity.getCredentialCiphertext().isBlank()
    );
    entity.setApiKey(null);
    return entity;
  }

  @Override
  protected Set<Map<String, Object>> getButtonDeclarationsSchema(
      final Class<AiProviderDefinition> domainClass
  ) {
    if (!scopeAccessPolicy.canConfigureCurrentScope()) {
      return Set.of();
    }
    String authorityPrefix = scopeAccessPolicy.currentManagementScope().scopeType()
        == AiResourceScope.SYSTEM ? "ai.system.providers." : "ai.providers.";
    return super.getButtonDeclarationsSchema(domainClass).stream()
        .filter(button -> String.valueOf(button.get("authority")).startsWith(authorityPrefix))
        .collect(Collectors.toSet());
  }

  private void assertCanRead(final AiProviderDefinition provider) {
    scopeAccessPolicy.assertCanReadManagedResource(
        provider.getScopeType(),
        provider.getTenantId()
    );
  }

  private static String defaultBaseUrl(final AiProviderType providerType) {
    return switch (providerType) {
      case OPENAI -> OPENAI_BASE_URL;
      case ANTHROPIC -> ANTHROPIC_BASE_URL;
      case OPENAI_COMPATIBLE -> throw new IllegalArgumentException("兼容协议必须配置 Base URL");
    };
  }

  private static void normalizePrivateNetworkAccess(
      final AiProviderDefinition entity,
      final AiResourceScope scopeType,
      final AiProviderDefinition current
  ) {
    Boolean requested = entity.getAllowPrivateNetwork();
    if (scopeType != AiResourceScope.SYSTEM && Boolean.TRUE.equals(requested)) {
      throw new IllegalArgumentException("租户供应商不允许访问内网地址");
    }
    if (scopeType != AiResourceScope.SYSTEM) {
      entity.setAllowPrivateNetwork(Boolean.FALSE);
      return;
    }
    if (requested == null && current != null) {
      requested = current.getAllowPrivateNetwork();
    }
    entity.setAllowPrivateNetwork(Boolean.TRUE.equals(requested));
  }

  private static String validateBaseUrl(final String value) {
    try {
      URI uri = URI.create(value.trim());
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException("Base URL 必须是有效的 http 或 https 地址");
      }
      return uri.toString().replaceAll("/+$", "");
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Base URL 必须是有效的 http 或 https 地址", ex);
    }
  }

  private static String normalizeCode(final String value) {
    String code = requireValue(value, "供应商编码不能为空").toLowerCase(Locale.ROOT);
    if (!code.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
      throw new IllegalArgumentException("供应商编码仅支持小写字母、数字、下划线和连字符");
    }
    return code;
  }

  private static String requireValue(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String requireId(final String id) {
    return requireValue(id, "供应商 ID 不能为空");
  }

  private static String requireEntityId(final AiProviderDefinition entity) {
    if (entity == null) {
      throw new IllegalArgumentException("AI 供应商配置不能为空");
    }
    return requireId(entity.getId());
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static void normalizeLikeQuery(
      final Map<String, String> attributes,
      final String field
  ) {
    String value = attributes.get(field);
    if (value != null && !value.isBlank() && !value.contains(":")) {
      attributes.put(field, "like:" + value.trim());
    }
  }
}
