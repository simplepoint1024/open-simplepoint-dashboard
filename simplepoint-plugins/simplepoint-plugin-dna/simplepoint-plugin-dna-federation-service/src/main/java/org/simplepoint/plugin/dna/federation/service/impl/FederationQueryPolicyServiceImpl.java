package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.normalizeLikeQuery;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireEntityId;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;
import org.simplepoint.plugin.dna.federation.api.repository.FederationCatalogRepository;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryPolicyRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryPolicyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Federation query policy service implementation.
 */
@Service
public class FederationQueryPolicyServiceImpl
    extends BaseServiceImpl<FederationQueryPolicyRepository, FederationQueryPolicy, String>
    implements FederationQueryPolicyService {

  private final FederationQueryPolicyRepository repository;

  private final FederationCatalogRepository catalogRepository;

  /**
   * Creates a federation query policy service implementation.
   *
   * @param repository             policy repository
   * @param detailsProviderService details provider service
   * @param catalogRepository      catalog repository
   */
  public FederationQueryPolicyServiceImpl(
      final FederationQueryPolicyRepository repository,
      final DetailsProviderService detailsProviderService,
      final FederationCatalogRepository catalogRepository
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.catalogRepository = catalogRepository;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationQueryPolicy> findActiveById(final String id) {
    return repository.findActiveById(id).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationQueryPolicy> findActiveByCode(final String code) {
    return repository.findActiveByCode(trimToNull(code)).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationQueryPolicy> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "code");
    Page<S> page = super.limit(normalized, pageable);
    decorate(page.getContent());
    return page;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationQueryPolicy> S create(final S entity) {
    FederationCatalog catalog = normalizeAndValidate(entity, null);
    applyDefaults(entity);
    S saved = super.create(entity);
    decorate(saved, catalog);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationQueryPolicy> FederationQueryPolicy modifyById(final S entity) {
    FederationQueryPolicy current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("查询策略不存在: " + entity.getId()));
    normalizeAndValidate(entity, current.getId());
    if (entity.getAllowSqlConsole() == null) {
      entity.setAllowSqlConsole(current.getAllowSqlConsole());
    }
    if (entity.getAllowCrossSourceJoin() == null) {
      entity.setAllowCrossSourceJoin(current.getAllowCrossSourceJoin());
    }
    if (entity.getMaxRows() == null) {
      entity.setMaxRows(current.getMaxRows());
    }
    if (entity.getTimeoutMs() == null) {
      entity.setTimeoutMs(current.getTimeoutMs());
    }
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    FederationQueryPolicy updated = (FederationQueryPolicy) super.modifyById(entity);
    FederationCatalog catalog = catalogRepository.findActiveById(updated.getCatalogId())
        .orElseThrow(() -> new IllegalArgumentException("联邦目录不存在: " + updated.getCatalogId()));
    decorate(updated, catalog);
    return updated;
  }

  private FederationCatalog normalizeAndValidate(final FederationQueryPolicy entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("查询策略不能为空");
    }
    entity.setCatalogId(requireValue(entity.getCatalogId(), "联邦目录不能为空"));
    entity.setName(requireValue(entity.getName(), "查询策略名称不能为空"));
    entity.setCode(requireValue(entity.getCode(), "查询策略编码不能为空"));
    entity.setDescription(trimToNull(entity.getDescription()));
    repository.findActiveByCode(entity.getCode())
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("查询策略编码已存在: " + entity.getCode());
        });
    if (entity.getMaxRows() != null && entity.getMaxRows() < 1) {
      throw new IllegalArgumentException("最大返回行数必须大于 0");
    }
    if (entity.getTimeoutMs() != null && entity.getTimeoutMs() < 1) {
      throw new IllegalArgumentException("超时毫秒数必须大于 0");
    }
    FederationCatalog catalog = catalogRepository.findActiveById(entity.getCatalogId())
        .orElseThrow(() -> new IllegalArgumentException("联邦目录不存在: " + entity.getCatalogId()));
    return catalog;
  }

  private void applyDefaults(final FederationQueryPolicy entity) {
    if (entity.getAllowSqlConsole() == null) {
      entity.setAllowSqlConsole(false);
    }
    if (entity.getAllowCrossSourceJoin() == null) {
      entity.setAllowCrossSourceJoin(false);
    }
    if (entity.getMaxRows() == null) {
      entity.setMaxRows(5000);
    }
    if (entity.getTimeoutMs() == null) {
      entity.setTimeoutMs(30000);
    }
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
  }

  private FederationQueryPolicy decorate(final FederationQueryPolicy item) {
    if (item == null) {
      return null;
    }
    catalogRepository.findActiveById(item.getCatalogId()).ifPresentOrElse(catalog -> decorate(item, catalog), () -> {
      item.setCatalogCode(null);
      item.setCatalogName(null);
    });
    return item;
  }

  private void decorate(final FederationQueryPolicy item, final FederationCatalog catalog) {
    item.setCatalogCode(catalog.getCode());
    item.setCatalogName(catalog.getName());
  }

  private <S extends FederationQueryPolicy> void decorate(final Collection<S> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    Set<String> catalogIds = items.stream()
        .map(FederationQueryPolicy::getCatalogId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    Map<String, FederationCatalog> catalogsById = catalogRepository.findAllByIds(catalogIds).stream()
        .collect(Collectors.toMap(FederationCatalog::getId, catalog -> catalog, (left, right) -> left));
    items.forEach(item -> {
      FederationCatalog catalog = catalogsById.get(item.getCatalogId());
      if (catalog != null) {
        decorate(item, catalog);
        return;
      }
      item.setCatalogCode(null);
      item.setCatalogName(null);
    });
  }
}
