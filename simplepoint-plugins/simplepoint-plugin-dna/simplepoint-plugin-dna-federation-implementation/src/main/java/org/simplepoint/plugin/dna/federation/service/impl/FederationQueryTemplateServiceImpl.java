package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.normalizeLikeQuery;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireEntityId;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

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
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryTemplate;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryTemplateRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryTemplateService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Federation query template service implementation.
 */
@Service
public class FederationQueryTemplateServiceImpl
    extends BaseServiceImpl<FederationQueryTemplateRepository, FederationQueryTemplate, String>
    implements FederationQueryTemplateService {

  private final FederationQueryTemplateRepository repository;

  private final JdbcDataSourceDefinitionService dataSourceService;

  /**
   * Creates a federation query template service implementation.
   *
   * @param repository             template repository
   * @param detailsProviderService details provider service
   * @param dataSourceService      datasource service
   */
  public FederationQueryTemplateServiceImpl(
      final FederationQueryTemplateRepository repository,
      final DetailsProviderService detailsProviderService,
      final JdbcDataSourceDefinitionService dataSourceService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.dataSourceService = dataSourceService;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationQueryTemplate> findActiveById(final String id) {
    return repository.findActiveById(id).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationQueryTemplate> findActiveByCode(final String code) {
    return repository.findActiveByCode(trimToNull(code)).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public List<FederationQueryTemplate> findAllActivePublic() {
    List<FederationQueryTemplate> templates = repository.findAllActivePublic();
    decorate(templates);
    return templates;
  }

  /** {@inheritDoc} */
  @Override
  public long countActivePublic() {
    return repository.findAllActivePublic().size();
  }

  /** {@inheritDoc} */
  @Override
  public long countActivePrivate() {
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("deletedAt", "is:null");
    attrs.put("isPublic", "false");
    return super.limit(attrs, Pageable.ofSize(1)).getTotalElements();
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationQueryTemplate> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "code");
    normalizeLikeQuery(normalized, "tags");
    Page<S> page = super.limit(normalized, pageable);
    decorate(page.getContent());
    return page;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationQueryTemplate> S create(final S entity) {
    normalizeAndValidate(entity, null);
    applyDefaults(entity);
    S saved = super.create(entity);
    decorate(saved);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationQueryTemplate> FederationQueryTemplate modifyById(final S entity) {
    FederationQueryTemplate current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("查询模板不存在: " + entity.getId()));
    normalizeAndValidate(entity, current.getId());
    if (entity.getIsPublic() == null) {
      entity.setIsPublic(current.getIsPublic());
    }
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    FederationQueryTemplate updated = (FederationQueryTemplate) super.modifyById(entity);
    decorate(updated);
    return updated;
  }

  private void normalizeAndValidate(final FederationQueryTemplate entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("查询模板不能为空");
    }
    entity.setName(requireValue(entity.getName(), "模板名称不能为空"));
    entity.setCode(requireValue(entity.getCode(), "模板编码不能为空"));
    entity.setSql(requireValue(entity.getSql(), "SQL 不能为空"));
    entity.setCatalogId(trimToNull(entity.getCatalogId()));
    entity.setDescription(trimToNull(entity.getDescription()));
    entity.setTags(trimToNull(entity.getTags()));
    repository.findActiveByCode(entity.getCode())
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("模板编码已存在: " + entity.getCode());
        });
    if (entity.getCatalogId() != null) {
      dataSourceService.findActiveById(entity.getCatalogId())
          .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + entity.getCatalogId()));
    }
  }

  private void applyDefaults(final FederationQueryTemplate entity) {
    if (entity.getIsPublic() == null) {
      entity.setIsPublic(false);
    }
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
  }

  private FederationQueryTemplate decorate(final FederationQueryTemplate item) {
    if (item == null) {
      return null;
    }
    if (item.getCatalogId() != null) {
      dataSourceService.findActiveById(item.getCatalogId()).ifPresentOrElse(
          dataSource -> decorate(item, dataSource),
          () -> {
            item.setCatalogCode(null);
            item.setCatalogName(null);
          }
      );
    }
    return item;
  }

  private void decorate(final FederationQueryTemplate item, final JdbcDataSourceDefinition dataSource) {
    item.setCatalogCode(dataSource.getCode());
    item.setCatalogName(dataSource.getName());
  }

  private <S extends FederationQueryTemplate> void decorate(final Collection<S> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    Set<String> catalogIds = items.stream()
        .map(FederationQueryTemplate::getCatalogId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    Map<String, JdbcDataSourceDefinition> dataSourcesById = catalogIds.stream()
        .map(dataSourceService::findActiveById)
        .flatMap(Optional::stream)
        .collect(Collectors.toMap(JdbcDataSourceDefinition::getId, ds -> ds, (l, r) -> l));
    items.forEach(item -> {
      if (item.getCatalogId() == null) {
        return;
      }
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
