package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.normalizeLikeQuery;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireEntityId;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.constants.FederationCatalogTypes;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.repository.FederationCatalogRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationCatalogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Federation catalog service implementation.
 */
@Service
public class FederationCatalogServiceImpl
    extends BaseServiceImpl<FederationCatalogRepository, FederationCatalog, String>
    implements FederationCatalogService {

  private static final String DATA_SOURCE_CATALOG_ID_PREFIX = "data-source:";

  private final FederationCatalogRepository repository;

  private final JdbcDataSourceDefinitionService dataSourceService;

  /**
   * Creates a federation catalog service implementation.
   *
   * @param repository             catalog repository
   * @param detailsProviderService details provider service
   */
  public FederationCatalogServiceImpl(
      final FederationCatalogRepository repository,
      final DetailsProviderService detailsProviderService,
      final JdbcDataSourceDefinitionService dataSourceService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.dataSourceService = dataSourceService;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationCatalog> findActiveById(final String id) {
    String normalizedId = trimToNull(id);
    if (normalizedId == null) {
      return Optional.empty();
    }
    Optional<FederationCatalog> persisted = repository.findActiveById(normalizedId).map(this::decoratePersistedCatalog);
    if (persisted.isPresent()) {
      return persisted;
    }
    return resolveDataSourceCatalogById(normalizedId);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationCatalog> findActiveByCode(final String code) {
    String normalizedCode = trimToNull(code);
    if (normalizedCode == null) {
      return Optional.empty();
    }
    Optional<FederationCatalog> persisted = repository.findActiveByCode(normalizedCode).map(this::decoratePersistedCatalog);
    if (persisted.isPresent()) {
      return persisted;
    }
    return dataSourceService.findActiveByCode(normalizedCode)
        .filter(definition -> Boolean.TRUE.equals(definition.getEnabled()))
        .map(this::toDataSourceCatalog);
  }

  /** {@inheritDoc} */
  @Override
  public List<FederationCatalog> findAllActiveByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return ids.stream()
        .map(this::findActiveById)
        .flatMap(Optional::stream)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationCatalog> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "code");
    Map<String, String> persistedFilters = new LinkedHashMap<>(normalized);
    persistedFilters.remove("catalogType");
    persistedFilters.remove("deletedAt");

    List<FederationCatalog> catalogs = new ArrayList<>();
    repository.findAll(persistedFilters).stream()
        .map(this::decoratePersistedCatalog)
        .forEach(catalogs::add);
    dataSourceService.listEnabledDefinitions().stream()
        .map(this::toDataSourceCatalog)
        .forEach(catalogs::add);

     List<FederationCatalog> filtered = catalogs.stream()
         .filter(catalog -> matchesCatalogFilters(catalog, normalized))
         .sorted(Comparator.comparingInt(FederationCatalogServiceImpl::catalogTypeOrder).thenComparing(
             FederationCatalog::getCode,
             Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
         ).thenComparing(
             FederationCatalog::getName,
             Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
        ).thenComparing(
            FederationCatalog::getId,
            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
        ))
        .toList();
    if (pageable.isUnpaged()) {
      @SuppressWarnings("unchecked")
      Page<S> page = (Page<S>) new PageImpl<>(filtered);
      return page;
    }
    int fromIndex = (int) Math.min(pageable.getOffset(), filtered.size());
    int toIndex = Math.min(fromIndex + pageable.getPageSize(), filtered.size());
    @SuppressWarnings("unchecked")
    Page<S> page = (Page<S>) new PageImpl<>(filtered.subList(fromIndex, toIndex), pageable, filtered.size());
    return page;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationCatalog> S create(final S entity) {
    normalizeAndValidate(entity, null);
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    return super.create(entity);
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationCatalog> FederationCatalog modifyById(final S entity) {
    String currentId = requireEntityId(entity);
    rejectReadOnlyCatalogId(currentId);
    FederationCatalog current = repository.findActiveById(currentId)
        .map(this::decoratePersistedCatalog)
        .orElseThrow(() -> new IllegalArgumentException("数据目录不存在: " + entity.getId()));
    normalizeAndValidate(entity, current.getId());
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    return (FederationCatalog) super.modifyById(entity);
  }

  /** {@inheritDoc} */
  @Override
  public void removeByIds(final Collection<String> ids) {
    if (ids != null) {
      ids.forEach(this::rejectReadOnlyCatalogId);
    }
    super.removeByIds(ids);
  }

  private void normalizeAndValidate(final FederationCatalog entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("数据目录不能为空");
    }
    String catalogType = FederationCatalogTypes.normalize(entity.getCatalogType());
    if (FederationCatalogTypes.isDataSource(catalogType)) {
      throw new IllegalArgumentException("数据源目录由系统根据启用数据源自动生成，不能手工新增或修改");
    }
    entity.setCatalogType(FederationCatalogTypes.VIRTUAL);
    entity.setName(requireValue(entity.getName(), "数据目录名称不能为空"));
    entity.setCode(requireValue(entity.getCode(), "数据目录编码不能为空"));
    entity.setDescription(trimToNull(entity.getDescription()));
    repository.findActiveByCode(entity.getCode())
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("数据目录编码已存在: " + entity.getCode());
        });
    dataSourceService.findActiveByCode(entity.getCode())
        .ifPresent(definition -> {
          throw new IllegalArgumentException("目录编码已被数据源目录占用: " + entity.getCode());
        });
  }

  private FederationCatalog decoratePersistedCatalog(final FederationCatalog catalog) {
    if (catalog == null) {
      return null;
    }
    catalog.setCatalogType(FederationCatalogTypes.normalize(catalog.getCatalogType()));
    return catalog;
  }

  private Optional<FederationCatalog> resolveDataSourceCatalogById(final String id) {
    if (id == null || !id.startsWith(DATA_SOURCE_CATALOG_ID_PREFIX)) {
      return Optional.empty();
    }
    String dataSourceId = trimToNull(id.substring(DATA_SOURCE_CATALOG_ID_PREFIX.length()));
    if (dataSourceId == null) {
      return Optional.empty();
    }
    return dataSourceService.findActiveById(dataSourceId)
        .filter(definition -> Boolean.TRUE.equals(definition.getEnabled()))
        .map(this::toDataSourceCatalog);
  }

  private FederationCatalog toDataSourceCatalog(final JdbcDataSourceDefinition definition) {
    FederationCatalog catalog = new FederationCatalog();
    String code = requireValue(definition == null ? null : definition.getCode(), "数据源目录编码不能为空");
    catalog.setId(dataSourceCatalogId(requireValue(definition.getId(), "数据源目录ID不能为空")));
    catalog.setName(code);
    catalog.setCode(code);
    catalog.setCatalogType(FederationCatalogTypes.DATA_SOURCE);
    catalog.setEnabled(Boolean.TRUE.equals(definition.getEnabled()));
    catalog.setDescription(trimToNull(definition.getDescription()));
    return catalog;
  }

  private static String dataSourceCatalogId(final String dataSourceId) {
    return DATA_SOURCE_CATALOG_ID_PREFIX + dataSourceId;
  }

  private void rejectReadOnlyCatalogId(final String id) {
    if (id != null && id.startsWith(DATA_SOURCE_CATALOG_ID_PREFIX)) {
      throw new IllegalArgumentException("数据源目录由系统自动生成，不支持编辑或删除");
    }
  }

  private static boolean matchesCatalogFilters(
      final FederationCatalog catalog,
      final Map<String, String> normalized
  ) {
    return matchesStringFilter(catalog.getName(), normalized.get("name"))
        && matchesStringFilter(catalog.getCode(), normalized.get("code"))
        && matchesStringFilter(catalog.getCatalogType(), normalized.get("catalogType"))
        && matchesBooleanFilter(catalog.getEnabled(), normalized.get("enabled"));
  }

  private static boolean matchesStringFilter(final String actualValue, final String filterValue) {
    String normalizedFilter = trimToNull(filterValue);
    if (normalizedFilter == null) {
      return true;
    }
    String normalizedActual = trimToNull(actualValue);
    if (normalizedFilter.startsWith("like:")) {
      return normalizedActual != null
          && normalizedActual.toLowerCase(java.util.Locale.ROOT).contains(
          normalizedFilter.substring(5).toLowerCase(java.util.Locale.ROOT)
      );
    }
    if (normalizedFilter.startsWith("equals:")) {
      normalizedFilter = trimToNull(normalizedFilter.substring(7));
    }
    return Objects.equals(
        normalizedActual == null ? null : normalizedActual.toLowerCase(java.util.Locale.ROOT),
        normalizedFilter == null ? null : normalizedFilter.toLowerCase(java.util.Locale.ROOT)
    );
  }

  private static boolean matchesBooleanFilter(final Boolean actualValue, final String filterValue) {
    String normalizedFilter = trimToNull(filterValue);
    if (normalizedFilter == null) {
      return true;
    }
    if (normalizedFilter.startsWith("equals:")) {
      normalizedFilter = trimToNull(normalizedFilter.substring(7));
    }
    if (normalizedFilter == null) {
      return true;
        }
        return Objects.equals(Boolean.valueOf(normalizedFilter), actualValue);
  }

  private static int catalogTypeOrder(final FederationCatalog catalog) {
    return FederationCatalogTypes.isDataSource(catalog == null ? null : catalog.getCatalogType()) ? 0 : 1;
  }
}
