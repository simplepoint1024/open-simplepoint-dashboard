package org.simplepoint.plugin.ai.mcp.service.impl;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpPublication;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.model.McpPublicationStatus;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerStatus;
import org.simplepoint.plugin.ai.mcp.api.properties.AiMcpProperties;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpPublicationRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpServerDefinitionRepository;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpPublicationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP publication management service.
 */
@Service
public class AiMcpPublicationServiceImpl
    extends BaseServiceImpl<AiMcpPublicationRepository, AiMcpPublication, String>
    implements AiMcpPublicationService {

  private final AiMcpPublicationRepository repository;

  private final AiMcpServerDefinitionRepository serverRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiMcpProperties properties;

  /**
   * Creates the publication service.
   */
  public AiMcpPublicationServiceImpl(
      final AiMcpPublicationRepository repository,
      final DetailsProviderService detailsProviderService,
      final AiMcpServerDefinitionRepository serverRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiMcpProperties properties
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.serverRepository = serverRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.properties = properties;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  public Optional<AiMcpPublication> findActiveById(final String id) {
    Optional<AiMcpPublication> publication = repository.findActiveById(required(
        id,
        "MCP publication ID must not be blank"
    ));
    publication.ifPresent(this::assertCanManage);
    return publication;
  }

  @Override
  public <S extends AiMcpPublication> Page<S> limit(
      final Map<String, String> attributes,
      final Pageable pageable
  ) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    normalized.put("scopeType", scope.scopeType().name());
    normalized.put("tenantId", scope.tenantId() == null ? "is:null" : scope.tenantId());
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "code");
    return super.limit(normalized, pageable);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends AiMcpPublication> S create(final S entity) {
    if (entity == null) {
      throw new IllegalArgumentException("MCP publication must not be null");
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    entity.setScopeType(scope.scopeType());
    entity.setTenantId(scope.tenantId());
    normalizeAndValidate(entity, null);
    S saved = super.create(entity);
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends AiMcpPublication> AiMcpPublication modifyById(final S entity) {
    if (entity == null || entity.getId() == null) {
      throw new IllegalArgumentException("MCP publication ID must not be blank");
    }
    AiMcpPublication current = repository.findActiveById(entity.getId())
        .orElseThrow(() -> new IllegalArgumentException("MCP publication does not exist"));
    assertCanManage(current);
    entity.setScopeType(current.getScopeType());
    entity.setTenantId(current.getTenantId());
    normalizeAndValidate(entity, current.getId());
    return (AiMcpPublication) super.modifyById(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    ids.forEach(id -> {
      AiMcpPublication publication = repository.findActiveById(id)
          .orElseThrow(() -> new IllegalArgumentException(
              "MCP publication does not exist"
          ));
      assertCanManage(publication);
    });
    super.removeByIds(ids);
  }

  private void normalizeAndValidate(
      final AiMcpPublication entity,
      final String currentId
  ) {
    entity.setName(required(entity.getName(), "MCP publication name must not be blank"));
    entity.setCode(normalizeCode(entity.getCode()));
    entity.setUpstreamServerId(required(
        entity.getUpstreamServerId(),
        "MCP upstream server must not be blank"
    ));
    AiMcpServerDefinition server = serverRepository
        .findActiveById(entity.getUpstreamServerId())
        .orElseThrow(() -> new IllegalArgumentException("MCP upstream server does not exist"));
    if (server.getScopeType() != entity.getScopeType()
        || !java.util.Objects.equals(server.getTenantId(), entity.getTenantId())) {
      throw new IllegalArgumentException(
          "MCP publication and upstream server must belong to the same scope"
      );
    }
    entity.setEnabled(entity.getEnabled() == null ? Boolean.FALSE : entity.getEnabled());
    if (Boolean.TRUE.equals(entity.getEnabled())
        && (server.getStatus() != McpServerStatus.READY
        || server.getActiveSnapshotId() == null)) {
      throw new IllegalArgumentException(
          "Only a READY MCP server can be published"
      );
    }
    entity.setRequiredScopes(normalizeScopes(entity.getRequiredScopes()));
    int rateLimit = entity.getRateLimitPerMinute() == null
        ? 60 : entity.getRateLimitPerMinute();
    if (rateLimit < 1 || rateLimit > 100000) {
      throw new IllegalArgumentException(
          "MCP publication rate limit must be between 1 and 100000 per minute"
      );
    }
    entity.setRateLimitPerMinute(rateLimit);
    entity.setCanonicalResourceUri(publicationResourceUri(entity.getCode()));
    entity.setAuthorizationServerUri(validateBaseUri(
        properties.getPublicationAuthorizationServerUrl(),
        "MCP publication authorization server URL",
        properties.isPublicationAllowInsecureHttp()
    ));
    entity.setStatus(Boolean.TRUE.equals(entity.getEnabled())
        ? McpPublicationStatus.PUBLISHED : McpPublicationStatus.DISABLED);
    entity.setDescription(trimToNull(entity.getDescription()));
    repository.findActiveByCode(entity.getCode()).ifPresent(existing -> {
      if (!existing.getId().equals(currentId)) {
        throw new IllegalArgumentException(
            "MCP publication code already exists: " + entity.getCode()
        );
      }
    });
  }

  private String publicationResourceUri(final String code) {
    return validateBaseUri(
        properties.getPublicationBaseUrl(),
        "MCP publication base URL",
        properties.isPublicationAllowInsecureHttp()
    ) + "/mcp/" + code;
  }

  private static String validateBaseUri(
      final String value,
      final String label,
      final boolean allowInsecureHttp
  ) {
    String normalized = required(value, label + " must not be blank")
        .replaceAll("/+$", "");
    try {
      URI uri = URI.create(normalized);
      String scheme = uri.getScheme() == null
          ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      boolean localhost = uri.getHost() != null
          && ("localhost".equalsIgnoreCase(uri.getHost())
          || uri.getHost().toLowerCase(Locale.ROOT).endsWith(".localhost"));
      if ((!"https".equals(scheme)
          && !("http".equals(scheme) && (localhost || allowInsecureHttp)))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("invalid");
      }
      return uri.toString().replaceAll("/+$", "");
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          label + " must use HTTPS unless insecure HTTP is explicitly enabled",
          ex
      );
    }
  }

  private static String normalizeCode(final String value) {
    String code = required(value, "MCP publication code must not be blank")
        .toLowerCase(Locale.ROOT);
    if (!code.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
      throw new IllegalArgumentException(
          "MCP publication code supports lowercase letters, digits, underscores, and hyphens"
      );
    }
    return code;
  }

  private static String normalizeScopes(final String value) {
    if (value == null || value.isBlank()) {
      return "mcp.invoke";
    }
    String normalized = java.util.Arrays.stream(value.trim().split("\\s+"))
        .filter(scope -> scope.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+"))
        .distinct()
        .collect(java.util.stream.Collectors.joining(" "));
    if (normalized.isBlank()) {
      throw new IllegalArgumentException("MCP publication scopes are invalid");
    }
    return normalized;
  }

  private void assertCanManage(final AiMcpPublication publication) {
    scopeAccessPolicy.assertCanManageOwnedResource(
        publication.getScopeType(),
        publication.getTenantId()
    );
  }

  private static String required(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static void normalizeLikeQuery(
      final Map<String, String> attributes,
      final String field
  ) {
    String value = attributes.get(field);
    if (value != null && !value.isBlank() && !value.contains(":")) {
      attributes.put(field, "like:" + value.trim());
    }
  }
}
