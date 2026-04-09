package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.normalizeLikeQuery;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryAuditRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Federation query audit service implementation.
 */
@Service
public class FederationQueryAuditServiceImpl
    extends BaseServiceImpl<FederationQueryAuditRepository, FederationQueryAudit, String>
    implements FederationQueryAuditService {

  private final FederationQueryAuditRepository repository;

  /**
   * Creates a federation query audit service implementation.
   *
   * @param repository             audit repository
   * @param detailsProviderService details provider service
   */
  public FederationQueryAuditServiceImpl(
      final FederationQueryAuditRepository repository,
      final DetailsProviderService detailsProviderService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<FederationQueryAudit> findActiveById(final String id) {
    return repository.findActiveById(id);
  }

  /** {@inheritDoc} */
  @Override
  public <S extends FederationQueryAudit> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "catalogCode");
    normalizeLikeQuery(normalized, "viewCode");
    normalizeLikeQuery(normalized, "status");
    return super.limit(normalized, pageable);
  }
}
