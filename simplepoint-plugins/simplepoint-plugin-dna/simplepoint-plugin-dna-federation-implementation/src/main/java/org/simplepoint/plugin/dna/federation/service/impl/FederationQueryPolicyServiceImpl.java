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
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;
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

  private final JdbcDataSourceDefinitionService dataSourceService;

  /**
   * Creates a federation query policy service implementation.
   *
   * @param repository policy repository
   * @param detailsProviderService details provider service
   * @param dataSourceService datasource service
   */
  public FederationQueryPolicyServiceImpl(
      final FederationQueryPolicyRepository repository,
      final DetailsProviderService detailsProviderService,
      final JdbcDataSourceDefinitionService dataSourceService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.dataSourceService = dataSourceService;
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
    JdbcDataSourceDefinition dataSource = normalizeAndValidate(entity, null);
    applyDefaults(entity);
    S saved = super.create(entity);
    decorate(saved, dataSource);
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
    JdbcDataSourceDefinition dataSource = dataSourceService.findActiveById(updated.getCatalogId())
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + updated.getCatalogId()));
    decorate(updated, dataSource);
    return updated;
  }

  private JdbcDataSourceDefinition normalizeAndValidate(final FederationQueryPolicy entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("查询策略不能为空");
    }
    entity.setCatalogId(requireValue(entity.getCatalogId(), "数据源不能为空"));
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
    return dataSourceService.findActiveById(entity.getCatalogId())
        .filter(dataSource -> Boolean.TRUE.equals(dataSource.getEnabled()))
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在或未启用: " + entity.getCatalogId()));
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
    dataSourceService.findActiveById(item.getCatalogId()).ifPresentOrElse(dataSource -> decorate(item, dataSource), () -> {
      item.setCatalogCode(null);
      item.setCatalogName(null);
    });
    return item;
  }

  private void decorate(final FederationQueryPolicy item, final JdbcDataSourceDefinition dataSource) {
    item.setCatalogCode(dataSource.getCode());
    item.setCatalogName(dataSource.getName());
  }

  private <S extends FederationQueryPolicy> void decorate(final Collection<S> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    Set<String> catalogIds = items.stream()
        .map(FederationQueryPolicy::getCatalogId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    Map<String, JdbcDataSourceDefinition> dataSourcesById = catalogIds.stream()
        .map(dataSourceService::findActiveById)
        .flatMap(Optional::stream)
        .collect(Collectors.toMap(JdbcDataSourceDefinition::getId, dataSource -> dataSource, (left, right) -> left));
    items.forEach(item -> {
      JdbcDataSourceDefinition dataSource = dataSourcesById.get(item.getCatalogId());
      if (dataSource != null) {
        decorate(item, dataSource);
        return;
      }
      item.setCatalogCode(null);
      item.setCatalogName(null);
    });
  }
}
