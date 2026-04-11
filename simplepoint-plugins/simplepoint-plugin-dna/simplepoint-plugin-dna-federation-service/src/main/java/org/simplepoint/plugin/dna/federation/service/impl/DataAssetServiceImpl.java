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
import org.simplepoint.plugin.dna.federation.api.entity.DataAsset;
import org.simplepoint.plugin.dna.federation.api.repository.DataAssetRepository;
import org.simplepoint.plugin.dna.federation.api.service.DataAssetService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Data asset service implementation.
 */
@Service
public class DataAssetServiceImpl
    extends BaseServiceImpl<DataAssetRepository, DataAsset, String>
    implements DataAssetService {

  private static final Set<String> VALID_ASSET_TYPES = Set.of("TABLE", "VIEW", "ALL");

  private final DataAssetRepository repository;

  private final JdbcDataSourceDefinitionService dataSourceService;

  /**
   * Creates a data asset service implementation.
   *
   * @param repository             asset repository
   * @param detailsProviderService details provider service
   * @param dataSourceService      datasource service
   */
  public DataAssetServiceImpl(
      final DataAssetRepository repository,
      final DetailsProviderService detailsProviderService,
      final JdbcDataSourceDefinitionService dataSourceService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.dataSourceService = dataSourceService;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<DataAsset> findActiveById(final String id) {
    return repository.findActiveById(id).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<DataAsset> findActiveByCode(final String code) {
    return repository.findActiveByCode(trimToNull(code)).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public long countActive() {
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("deletedAt", "is:null");
    attrs.put("enabled", "true");
    return super.limit(attrs, Pageable.ofSize(1)).getTotalElements();
  }

  /** {@inheritDoc} */
  @Override
  public <S extends DataAsset> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "code");
    normalizeLikeQuery(normalized, "tags");
    normalizeLikeQuery(normalized, "owner");
    Page<S> page = super.limit(normalized, pageable);
    decorate(page.getContent());
    return page;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends DataAsset> S create(final S entity) {
    normalizeAndValidate(entity, null);
    applyDefaults(entity);
    S saved = super.create(entity);
    decorate(saved);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends DataAsset> DataAsset modifyById(final S entity) {
    DataAsset current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("数据资产不存在: " + entity.getId()));
    normalizeAndValidate(entity, current.getId());
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    DataAsset updated = (DataAsset) super.modifyById(entity);
    decorate(updated);
    return updated;
  }

  private void normalizeAndValidate(final DataAsset entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("数据资产不能为空");
    }
    entity.setName(requireValue(entity.getName(), "资产名称不能为空"));
    entity.setCode(requireValue(entity.getCode(), "资产编码不能为空"));
    entity.setCatalogId(requireValue(entity.getCatalogId(), "数据源不能为空"));
    entity.setAssetType(requireValue(entity.getAssetType(), "资产类型不能为空"));
    entity.setDescription(trimToNull(entity.getDescription()));
    entity.setTags(trimToNull(entity.getTags()));
    entity.setOwner(trimToNull(entity.getOwner()));
    entity.setSchemaPattern(trimToNull(entity.getSchemaPattern()));
    entity.setTablePattern(trimToNull(entity.getTablePattern()));
    if (!VALID_ASSET_TYPES.contains(entity.getAssetType())) {
      throw new IllegalArgumentException("资产类型必须为 TABLE, VIEW 或 ALL");
    }
    repository.findActiveByCode(entity.getCode())
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("资产编码已存在: " + entity.getCode());
        });
    dataSourceService.findActiveById(entity.getCatalogId())
        .filter(ds -> Boolean.TRUE.equals(ds.getEnabled()))
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在或未启用: " + entity.getCatalogId()));
  }

  private void applyDefaults(final DataAsset entity) {
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
  }

  private DataAsset decorate(final DataAsset item) {
    if (item == null) {
      return null;
    }
    dataSourceService.findActiveById(item.getCatalogId()).ifPresentOrElse(
        ds -> {
          item.setCatalogCode(ds.getCode());
          item.setCatalogName(ds.getName());
        },
        () -> {
          item.setCatalogCode(null);
          item.setCatalogName(null);
        }
    );
    return item;
  }

  private <S extends DataAsset> void decorate(final Collection<S> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    Set<String> catalogIds = items.stream()
        .map(DataAsset::getCatalogId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    Map<String, JdbcDataSourceDefinition> dataSourcesById = catalogIds.stream()
        .map(dataSourceService::findActiveById)
        .flatMap(Optional::stream)
        .collect(Collectors.toMap(JdbcDataSourceDefinition::getId, ds -> ds, (l, r) -> l));
    items.forEach(item -> {
      JdbcDataSourceDefinition ds = dataSourcesById.get(item.getCatalogId());
      if (ds != null) {
        item.setCatalogCode(ds.getCode());
        item.setCatalogName(ds.getName());
        return;
      }
      item.setCatalogCode(null);
      item.setCatalogName(null);
    });
  }
}
