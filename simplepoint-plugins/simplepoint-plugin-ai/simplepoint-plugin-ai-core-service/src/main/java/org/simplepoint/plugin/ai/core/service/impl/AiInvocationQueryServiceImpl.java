package org.simplepoint.plugin.ai.core.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.ai.core.api.entity.AiInvocationRecord;
import org.simplepoint.plugin.ai.core.api.repository.AiInvocationRecordRepository;
import org.simplepoint.plugin.ai.core.api.service.AiInvocationQueryService;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** Scope-isolated, read-only invocation ledger query service. */
@Service
public class AiInvocationQueryServiceImpl
    extends BaseServiceImpl<AiInvocationRecordRepository, AiInvocationRecord, String>
    implements AiInvocationQueryService {

  private final AiInvocationRecordRepository repository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  /** Creates the query service. */
  public AiInvocationQueryServiceImpl(
      final AiInvocationRecordRepository repository,
      final DetailsProviderService detailsProviderService,
      final AiScopeAccessPolicy scopeAccessPolicy
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.scopeAccessPolicy = scopeAccessPolicy;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  public <S extends AiInvocationRecord> Page<S> limit(
      final Map<String, String> attributes,
      final Pageable pageable
  ) {
    Map<String, String> filters = new LinkedHashMap<>();
    copyFilter(attributes, filters, "operation");
    copyFilter(attributes, filters, "status");
    copyFilter(attributes, filters, "modelDefinitionId");
    copyFilter(attributes, filters, "modelId");
    copyFilter(attributes, filters, "userId");
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    filters.put("scopeType", scope.scopeType().name());
    filters.put("tenantId", scope.tenantId() == null ? "is:null" : scope.tenantId());
    filters.put("deletedAt", "is:null");
    return repository.limit(filters, pageable);
  }

  private static void copyFilter(
      final Map<String, String> source,
      final Map<String, String> target,
      final String name
  ) {
    if (source != null && source.get(name) != null && !source.get(name).isBlank()) {
      target.put(name, source.get(name).trim());
    }
  }
}
