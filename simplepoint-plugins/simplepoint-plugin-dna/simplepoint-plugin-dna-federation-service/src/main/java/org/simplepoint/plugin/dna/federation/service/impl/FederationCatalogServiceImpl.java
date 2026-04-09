package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.normalizeLikeQuery;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireEntityId;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.repository.FederationCatalogRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationCatalogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Federation catalog service implementation.
 */
@Service
public class FederationCatalogServiceImpl
    extends BaseServiceImpl<FederationCatalogRepository, FederationCatalog, String>
    implements FederationCatalogService {

  private final FederationCatalogRepository repository;

  /**
   * Creates a federation catalog service implementation.
   *
   * @param repository             catalog repository
   * @param detailsProviderService details provider service
   */
  public FederationCatalogServiceImpl(
      final FederationCatalogRepository repository,
      final DetailsProviderService detailsProviderService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationCatalog> findActiveById(final String id) {
    return repository.findActiveById(id);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationCatalog> findActiveByCode(final String code) {
    return repository.findActiveByCode(trimToNull(code));
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
    return super.limit(normalized, pageable);
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
    FederationCatalog current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("联邦目录不存在: " + entity.getId()));
    normalizeAndValidate(entity, current.getId());
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    return (FederationCatalog) super.modifyById(entity);
  }

  private void normalizeAndValidate(final FederationCatalog entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("联邦目录不能为空");
    }
    entity.setName(requireValue(entity.getName(), "联邦目录名称不能为空"));
    entity.setCode(requireValue(entity.getCode(), "联邦目录编码不能为空"));
    entity.setDescription(trimToNull(entity.getDescription()));
    repository.findActiveByCode(entity.getCode())
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("联邦目录编码已存在: " + entity.getCode());
        });
  }
}
