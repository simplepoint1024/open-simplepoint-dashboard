package org.simplepoint.plugin.ai.mcp.service.impl;

import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpInvocation;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpInvocationRepository;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpInvocationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Scope-isolated query service for the metadata-only invocation ledger. */
@Service
public class AiMcpInvocationServiceImpl implements AiMcpInvocationService {

  private final AiMcpInvocationRepository repository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  /** Creates the invocation ledger query service. */
  public AiMcpInvocationServiceImpl(
      final AiMcpInvocationRepository repository,
      final AiScopeAccessPolicy scopeAccessPolicy
  ) {
    this.repository = repository;
    this.scopeAccessPolicy = scopeAccessPolicy;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiMcpInvocation> findAll(
      final String serverId,
      final Pageable pageable
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    String filter = serverId == null || serverId.isBlank()
        ? null : serverId.trim();
    if (filter != null && !filter.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
      throw new IllegalArgumentException("MCP Server ID is invalid");
    }
    return repository.findAllActiveByScope(
        scope.scopeType(), scope.tenantId(), filter, pageable
    );
  }
}
