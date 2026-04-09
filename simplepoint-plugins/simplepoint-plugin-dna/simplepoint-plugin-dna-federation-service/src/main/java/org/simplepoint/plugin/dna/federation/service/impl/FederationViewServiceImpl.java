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
import org.simplepoint.plugin.dna.federation.api.entity.FederationSchema;
import org.simplepoint.plugin.dna.federation.api.entity.FederationView;
import org.simplepoint.plugin.dna.federation.api.repository.FederationSchemaRepository;
import org.simplepoint.plugin.dna.federation.api.repository.FederationViewRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationViewService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Federation view service implementation.
 */
@Service
public class FederationViewServiceImpl
    extends BaseServiceImpl<FederationViewRepository, FederationView, String>
    implements FederationViewService {

  private final FederationViewRepository repository;

  private final FederationSchemaRepository schemaRepository;

  /**
   * Creates a federation view service implementation.
   *
   * @param repository             view repository
   * @param detailsProviderService details provider service
   * @param schemaRepository       schema repository
   */
  public FederationViewServiceImpl(
      final FederationViewRepository repository,
      final DetailsProviderService detailsProviderService,
      final FederationSchemaRepository schemaRepository
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.schemaRepository = schemaRepository;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationView> findActiveById(final String id) {
    return repository.findActiveById(id).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationView> findActiveByCode(final String code) {
    return repository.findActiveByCode(trimToNull(code)).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationView> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
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
  public <S extends FederationView> S create(final S entity) {
    FederationSchema schema = normalizeAndValidate(entity, null);
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    S saved = super.create(entity);
    decorate(saved, schema);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationView> FederationView modifyById(final S entity) {
    FederationView current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("逻辑视图不存在: " + entity.getId()));
    normalizeAndValidate(entity, current.getId());
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    FederationView updated = (FederationView) super.modifyById(entity);
    FederationSchema schema = schemaRepository.findActiveById(updated.getSchemaId())
        .orElseThrow(() -> new IllegalArgumentException("逻辑 Schema 不存在: " + updated.getSchemaId()));
    decorate(updated, schema);
    return updated;
  }

  private FederationSchema normalizeAndValidate(final FederationView entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("逻辑视图不能为空");
    }
    entity.setSchemaId(requireValue(entity.getSchemaId(), "逻辑 Schema 不能为空"));
    entity.setName(requireValue(entity.getName(), "逻辑视图名称不能为空"));
    entity.setCode(requireValue(entity.getCode(), "逻辑视图编码不能为空"));
    entity.setDefinitionSql(requireValue(entity.getDefinitionSql(), "逻辑视图 SQL 不能为空"));
    entity.setDescription(trimToNull(entity.getDescription()));
    repository.findActiveByCode(entity.getCode())
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("逻辑视图编码已存在: " + entity.getCode());
        });
    FederationSchema schema = schemaRepository.findActiveById(entity.getSchemaId())
        .orElseThrow(() -> new IllegalArgumentException("逻辑 Schema 不存在: " + entity.getSchemaId()));
    return schema;
  }

  private FederationView decorate(final FederationView item) {
    if (item == null) {
      return null;
    }
    schemaRepository.findActiveById(item.getSchemaId()).ifPresentOrElse(schema -> decorate(item, schema), () -> {
      item.setSchemaCode(null);
      item.setSchemaName(null);
    });
    return item;
  }

  private void decorate(final FederationView item, final FederationSchema schema) {
    item.setSchemaCode(schema.getCode());
    item.setSchemaName(schema.getName());
  }

  private <S extends FederationView> void decorate(final Collection<S> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    Set<String> schemaIds = items.stream()
        .map(FederationView::getSchemaId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    Map<String, FederationSchema> schemasById = schemaRepository.findAllByIds(schemaIds).stream()
        .collect(Collectors.toMap(FederationSchema::getId, schema -> schema, (left, right) -> left));
    items.forEach(item -> {
      FederationSchema schema = schemasById.get(item.getSchemaId());
      if (schema != null) {
        decorate(item, schema);
        return;
      }
      item.setSchemaCode(null);
      item.setSchemaName(null);
    });
  }
}
