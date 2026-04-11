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
import org.simplepoint.plugin.dna.federation.api.constants.FederationCatalogTypes;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.entity.FederationSchema;
import org.simplepoint.plugin.dna.federation.api.repository.FederationSchemaRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationCatalogService;
import org.simplepoint.plugin.dna.federation.api.service.FederationSchemaService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Federation schema service implementation.
 */
@Service
public class FederationSchemaServiceImpl
    extends BaseServiceImpl<FederationSchemaRepository, FederationSchema, String>
    implements FederationSchemaService {

  private final FederationSchemaRepository repository;

  private final FederationCatalogService catalogService;

  /**
   * Creates a federation schema service implementation.
   *
   * @param repository             schema repository
   * @param detailsProviderService details provider service
   * @param catalogRepository      catalog repository
   */
  public FederationSchemaServiceImpl(
      final FederationSchemaRepository repository,
      final DetailsProviderService detailsProviderService,
      final FederationCatalogService catalogService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.catalogService = catalogService;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationSchema> findActiveById(final String id) {
    return repository.findActiveById(id).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationSchema> findActiveByCode(final String code) {
    return repository.findActiveByCode(trimToNull(code)).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationSchema> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
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
  public <S extends FederationSchema> S create(final S entity) {
    FederationCatalog catalog = normalizeAndValidate(entity, null);
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    S saved = super.create(entity);
    decorate(saved, catalog);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationSchema> FederationSchema modifyById(final S entity) {
    FederationSchema current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("逻辑 Schema 不存在: " + entity.getId()));
    normalizeAndValidate(entity, current.getId());
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    FederationSchema updated = (FederationSchema) super.modifyById(entity);
    FederationCatalog catalog = requireVirtualCatalog(updated.getCatalogId());
    decorate(updated, catalog);
    return updated;
  }

  private FederationCatalog normalizeAndValidate(final FederationSchema entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("逻辑 Schema 不能为空");
    }
    entity.setCatalogId(requireValue(entity.getCatalogId(), "联邦目录不能为空"));
    entity.setName(requireValue(entity.getName(), "逻辑 Schema 名称不能为空"));
    entity.setCode(requireValue(entity.getCode(), "逻辑 Schema 编码不能为空"));
    entity.setDescription(trimToNull(entity.getDescription()));
    repository.findActiveByCode(entity.getCode())
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("逻辑 Schema 编码已存在: " + entity.getCode());
        });
    return requireVirtualCatalog(entity.getCatalogId());
  }

  private FederationSchema decorate(final FederationSchema item) {
    if (item == null) {
      return null;
    }
    catalogService.findActiveById(item.getCatalogId()).ifPresentOrElse(catalog -> decorate(item, catalog), () -> {
      item.setCatalogCode(null);
      item.setCatalogName(null);
    });
    return item;
  }

  private void decorate(final FederationSchema item, final FederationCatalog catalog) {
    item.setCatalogCode(catalog.getCode());
    item.setCatalogName(catalog.getName());
  }

  private <S extends FederationSchema> void decorate(final Collection<S> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    Set<String> catalogIds = items.stream()
        .map(FederationSchema::getCatalogId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    Map<String, FederationCatalog> catalogsById = catalogService.findAllActiveByIds(catalogIds).stream()
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

  private FederationCatalog requireVirtualCatalog(final String catalogId) {
    FederationCatalog catalog = catalogService.findActiveById(catalogId)
        .orElseThrow(() -> new IllegalArgumentException("联邦目录不存在: " + catalogId));
    if (!FederationCatalogTypes.isVirtual(catalog.getCatalogType())) {
      throw new IllegalArgumentException("仅虚拟目录支持创建逻辑 Schema");
    }
    return catalog;
  }
}
