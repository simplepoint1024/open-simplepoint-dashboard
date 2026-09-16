package org.simplepoint.plugin.ai.core.service.impl;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.service.AiModelDefinitionService;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Model definition service implementation.
 */
@Service
public class AiModelDefinitionServiceImpl
    extends BaseServiceImpl<AiModelDefinitionRepository, AiModelDefinition, String>
    implements AiModelDefinitionService {

  private static final BigDecimal MAX_PRICE = new BigDecimal("99999999999.99999999");

  private final AiModelDefinitionRepository repository;

  private final AiProviderDefinitionRepository providerRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  /**
   * Creates the model service.
   *
   * @param repository             model repository
   * @param detailsProviderService details provider
   * @param providerRepository     provider repository
   * @param scopeAccessPolicy      AI resource scope policy
   */
  public AiModelDefinitionServiceImpl(
      final AiModelDefinitionRepository repository,
      final DetailsProviderService detailsProviderService,
      final AiProviderDefinitionRepository providerRepository,
      final AiScopeAccessPolicy scopeAccessPolicy
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.providerRepository = providerRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends AiModelDefinition> Page<S> limit(
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
    normalizeLikeQuery(normalized, "modelId");
    normalizeLikeQuery(normalized, "displayName");
    Page<S> page = super.limit(normalized, pageable);
    decorate(page.getContent());
    return page;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends AiModelDefinition> S create(final S entity) {
    normalizeAndValidate(entity, null);
    entity.setEnabled(entity.getEnabled() == null ? Boolean.TRUE : entity.getEnabled());
    entity.setAvailable(Boolean.TRUE);
    entity.setDiscovered(Boolean.FALSE);
    entity.setTypeAutoDetected(Boolean.FALSE);
    entity.setPricingAutoDetected(Boolean.FALSE);
    S saved = super.create(entity);
    decorate(saved);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends AiModelDefinition> AiModelDefinition modifyById(final S entity) {
    AiModelDefinition current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("模型不存在: " + entity.getId()));
    scopeAccessPolicy.assertCanWriteResource(current.getScopeType(), current.getTenantId());
    normalizeAndValidate(entity, current.getId());
    entity.setAvailable(current.getAvailable());
    entity.setDiscovered(current.getDiscovered());
    entity.setOwnedBy(current.getOwnedBy());
    entity.setReleasedAt(current.getReleasedAt());
    entity.setMetadataJson(current.getMetadataJson());
    entity.setTypeAutoDetected(current.getTypeAutoDetected());
    entity.setPricingAutoDetected(current.getPricingAutoDetected());
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    boolean modelTypeChanged = entity.getModelType() != current.getModelType();
    boolean pricingChanged = pricingChanged(entity, current);
    AiModelDefinition updated = (AiModelDefinition) super.modifyById(entity);
    if (modelTypeChanged && Boolean.TRUE.equals(updated.getTypeAutoDetected())) {
      updated.setTypeAutoDetected(Boolean.FALSE);
      updated = repository.save(updated);
    }
    if (pricingChanged && Boolean.TRUE.equals(updated.getPricingAutoDetected())) {
      updated.setPricingAutoDetected(Boolean.FALSE);
      updated = repository.save(updated);
    }
    decorate(updated);
    return updated;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<AiModelDefinition> findById(final String id) {
    Optional<AiModelDefinition> model = repository.findActiveById(requireValue(id, "模型 ID 不能为空"));
    model.ifPresent(item -> scopeAccessPolicy.assertCanReadManagedResource(
        item.getScopeType(),
        item.getTenantId()
    ));
    model.ifPresent(this::decorate);
    return model;
  }

  /** {@inheritDoc} */
  @Override
  public List<AiModelDefinition> listAvailableModels() {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    List<AiModelDefinition> models = scope.scopeType() == AiResourceScope.SYSTEM
        ? repository.findAllAvailableSystemModels()
        : repository.findAllAvailableForTenant(scope.tenantId());
    decorate(models);
    return models;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    List<AiModelDefinition> models = ids.stream()
        .map(repository::findActiveById)
        .map(model -> model.orElseThrow(() ->
            new IllegalArgumentException("AI 模型不存在或无权访问")))
        .toList();
    models.forEach(model -> scopeAccessPolicy.assertCanWriteResource(
        model.getScopeType(),
        model.getTenantId()
    ));
    super.removeByIds(ids);
  }

  private void normalizeAndValidate(final AiModelDefinition entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("模型配置不能为空");
    }
    entity.setProviderId(requireValue(entity.getProviderId(), "供应商不能为空"));
    entity.setModelId(requireValue(entity.getModelId(), "模型 ID 不能为空"));
    entity.setDisplayName(trimToNull(entity.getDisplayName()));
    entity.setDescription(trimToNull(entity.getDescription()));
    normalizePricing(entity);
    if (entity.getModelType() == null) {
      entity.setModelType(AiModelType.LLM);
    }
    AiProviderDefinition provider = providerRepository.findActiveById(entity.getProviderId())
        .orElseThrow(() -> new IllegalArgumentException("供应商不存在: " + entity.getProviderId()));
    scopeAccessPolicy.assertCanWriteResource(provider.getScopeType(), provider.getTenantId());
    entity.setScopeType(AiScopeAccessPolicy.effectiveScope(provider.getScopeType()));
    entity.setTenantId(provider.getTenantId());
    repository.findActiveByProviderAndModelId(entity.getProviderId(), entity.getModelId())
        .ifPresent(existing -> {
          if (!existing.getId().equals(currentId)) {
            throw new IllegalArgumentException("该供应商下已存在此模型: " + entity.getModelId());
          }
        });
  }

  private static void normalizePricing(final AiModelDefinition entity) {
    entity.setBillingEnabled(Boolean.TRUE.equals(entity.getBillingEnabled()));
    String currency = trimToNull(entity.getBillingCurrency());
    if (currency == null) {
      currency = "USD";
    }
    currency = currency.toUpperCase(java.util.Locale.ROOT);
    try {
      Currency.getInstance(currency);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("计费币种必须是有效的 ISO 4217 三字母代码", ex);
    }
    entity.setBillingCurrency(currency);
    entity.setInputTokenPrice(normalizePrice(
        entity.getInputTokenPrice(), "输入 Token 单价"
    ));
    entity.setCachedInputTokenPrice(normalizePrice(
        entity.getCachedInputTokenPrice(), "缓存输入 Token 单价"
    ));
    entity.setOutputTokenPrice(normalizePrice(
        entity.getOutputTokenPrice(), "输出 Token 单价"
    ));
    entity.setRequestPrice(normalizePrice(
        entity.getRequestPrice(), "单次请求价格"
    ));
    if (Boolean.TRUE.equals(entity.getBillingEnabled())
        && entity.getInputTokenPrice() == null
        && entity.getCachedInputTokenPrice() == null
        && entity.getOutputTokenPrice() == null
        && entity.getRequestPrice() == null) {
      throw new IllegalArgumentException("启用计费时至少需要配置一项价格，免费模型请显式填写 0");
    }
  }

  private static BigDecimal normalizePrice(
      final BigDecimal value,
      final String name
  ) {
    if (value == null) {
      return null;
    }
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + "不能小于 0");
    }
    if (value.scale() > 8) {
      throw new IllegalArgumentException(name + "最多支持 8 位小数");
    }
    if (value.compareTo(MAX_PRICE) > 0) {
      throw new IllegalArgumentException(name + "超出允许范围");
    }
    return value;
  }

  private static boolean pricingChanged(
      final AiModelDefinition requested,
      final AiModelDefinition current
  ) {
    return !Objects.equals(requested.getBillingEnabled(), current.getBillingEnabled())
        || !Objects.equals(requested.getBillingCurrency(), current.getBillingCurrency())
        || !Objects.equals(requested.getInputTokenPrice(), current.getInputTokenPrice())
        || !Objects.equals(
            requested.getCachedInputTokenPrice(), current.getCachedInputTokenPrice()
        )
        || !Objects.equals(requested.getOutputTokenPrice(), current.getOutputTokenPrice())
        || !Objects.equals(requested.getRequestPrice(), current.getRequestPrice());
  }

  private void decorate(final Iterable<? extends AiModelDefinition> models) {
    Map<String, String> providerNames = new java.util.HashMap<>();
    for (AiModelDefinition model : models) {
      model.setScopeType(AiScopeAccessPolicy.effectiveScope(model.getScopeType()));
      String name = providerNames.computeIfAbsent(model.getProviderId(), id -> providerRepository
          .findActiveById(id)
          .map(AiProviderDefinition::getName)
          .orElse(id));
      model.setProviderName(name);
    }
  }

  private void decorate(final AiModelDefinition model) {
    decorate(java.util.List.of(model));
  }

  @Override
  protected Set<Map<String, Object>> getButtonDeclarationsSchema(
      final Class<AiModelDefinition> domainClass
  ) {
    Set<Map<String, Object>> declarations = super.getButtonDeclarationsSchema(domainClass);
    if (scopeAccessPolicy.canConfigureCurrentScope()) {
      return declarations;
    }
    return declarations.stream()
        .filter(button -> "ai.workbench.models.debug".equals(button.get("authority")))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static String requireEntityId(final AiModelDefinition entity) {
    if (entity == null || entity.getId() == null || entity.getId().isBlank()) {
      throw new IllegalArgumentException("模型 ID 不能为空");
    }
    return entity.getId().trim();
  }

  private static String requireValue(final String value, final String message) {
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
