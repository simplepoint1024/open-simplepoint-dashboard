package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.constants.FederationJdbcOperation;
import org.simplepoint.plugin.dna.federation.api.entity.FederationJdbcConnectionUser;
import org.simplepoint.plugin.dna.federation.api.service.FederationJdbcConnectionUserService;
import org.simplepoint.plugin.dna.federation.api.service.FederationJdbcDriverService;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.simplepoint.plugin.dna.federation.service.support.FederationMetadataCacheService;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException.Code;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialIdentity;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialRequest;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialSession;
import org.simplepoint.plugin.rbac.core.api.service.CredentialAuthenticationService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.security.context.AuthorizationContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** External DNA JDBC driver gateway service implementation. */
@Service
public class FederationJdbcDriverServiceImpl implements FederationJdbcDriverService {

  private static final Logger LOG = LoggerFactory.getLogger(FederationJdbcDriverServiceImpl.class);
  private static final String DATABASE_PRODUCT_NAME = "SimplePoint DNA Federation";

  private final JdbcDataSourceDefinitionService dataSourceService;

  private final FederationJdbcConnectionUserService jdbcConnectionUserService;

  private final FederationSqlConsoleService sqlConsoleService;

  private final FederationJdbcMetadataQueryService metadataQueryService;

  private final CredentialAuthenticationService credentials;

  private final TenantDirectory tenantService;

  private final AuthorizationContextService authorizationContextService;

  private final FederationMetadataCacheService metadataCacheService;

  /**
   * Creates a JDBC driver gateway service.
   *
   * @param dataSourceService datasource service
   * @param jdbcConnectionUserService jdbc grant service
   * @param sqlConsoleService sql console service
   * @param metadataQueryService jdbc metadata query service
   * @param credentials identity-owned credential verification
   * @param tenantService tenant service
   * @param authorizationContextService authorization context service
   * @param metadataCacheService global metadata cache service
   */
  public FederationJdbcDriverServiceImpl(
      final JdbcDataSourceDefinitionService dataSourceService,
      final FederationJdbcConnectionUserService jdbcConnectionUserService,
      final FederationSqlConsoleService sqlConsoleService,
      final FederationJdbcMetadataQueryService metadataQueryService,
      final CredentialAuthenticationService credentials,
      final TenantDirectory tenantService,
      final AuthorizationContextService authorizationContextService,
      final FederationMetadataCacheService metadataCacheService) {
    this.dataSourceService = dataSourceService;
    this.jdbcConnectionUserService = jdbcConnectionUserService;
    this.sqlConsoleService = sqlConsoleService;
    this.metadataQueryService = metadataQueryService;
    this.credentials = credentials;
    this.tenantService = tenantService;
    this.authorizationContextService = authorizationContextService;
    this.metadataCacheService = metadataCacheService;
  }

  @Override
  public FederationJdbcDriverModels.PingResult ping(
      final FederationJdbcDriverModels.DriverRequest request) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return ping(session, request == null ? null : request.contextId());
    }
  }

  @Override
  public FederationJdbcDriverModels.PingResult ping(
      final FederationJdbcDriverService.DriverSession session, final String contextId) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(
        requiredSession,
        contextId,
        requiredSession.selectedCatalogCode(),
        (resolvedSession, resolvedContextId) ->
            new FederationJdbcDriverModels.PingResult(
                resolvedSession.selectedCatalogCode(),
                resolvedSession.tenantId(),
                resolvedContextId,
                resolvedSession.userId(),
                resolvedSession.loginSubject(),
                DATABASE_PRODUCT_NAME,
                resolveImplementationVersion(),
                null));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult catalogs(
      final FederationJdbcDriverModels.DriverRequest request) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return catalogs(session, request == null ? null : request.contextId());
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult catalogs(
      final FederationJdbcDriverService.DriverSession session, final String contextId) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(
        requiredSession,
        contextId,
        requiredSession.selectedCatalogCode(),
        (resolvedSession, resolvedContextId) ->
            requiredSession.cachedMetadata(
                "catalogs",
                () ->
                    metadataQueryService.catalogs(
                        toDataSourceList(requiredSession.requireMetadataDataSources())),
                metadataCacheService));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult schemas(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalogPattern,
      final String schemaPattern) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return schemas(
          session, request == null ? null : request.contextId(), catalogPattern, schemaPattern);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult schemas(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalogPattern,
      final String schemaPattern) {
    JdbcConnectionSession requiredSession = requireSession(session);
    // CHECKSTYLE.SUPPRESS: LineLength for +1 lines
    return withDriverContext(
        requiredSession,
        contextId,
        resolveMetadataContextCatalog(requiredSession, catalogPattern),
        (resolvedSession, resolvedContextId) ->
            requiredSession.cachedMetadata(
                "schemas:"
                    + normalizedCacheValue(catalogPattern)
                    + ':'
                    + normalizedCacheValue(schemaPattern),
                () ->
                    metadataQueryService.schemas(
                        toDataSourceList(requiredSession.requireMetadataDataSources()),
                        catalogPattern,
                        schemaPattern),
                metadataCacheService));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult tableTypes(
      final FederationJdbcDriverModels.DriverRequest request) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return tableTypes(session, request == null ? null : request.contextId());
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult tableTypes(
      final FederationJdbcDriverService.DriverSession session, final String contextId) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(
        requiredSession,
        contextId,
        requiredSession.selectedCatalogCode(),
        (resolvedSession, resolvedContextId) ->
            requiredSession.cachedMetadata(
                "tableTypes",
                () ->
                    metadataQueryService.tableTypes(
                        toDataSourceList(requiredSession.requireMetadataDataSources())),
                metadataCacheService));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult tables(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final List<String> types) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return tables(
          session,
          request == null ? null : request.contextId(),
          catalogPattern,
          schemaPattern,
          tablePattern,
          types);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult tables(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final List<String> types) {
    JdbcConnectionSession requiredSession = requireSession(session);
    // CHECKSTYLE.SUPPRESS: LineLength for +1 lines
    return withDriverContext(
        requiredSession,
        contextId,
        resolveMetadataContextCatalog(requiredSession, catalogPattern),
        (resolvedSession, resolvedContextId) ->
            requiredSession.cachedMetadata(
                "tables:"
                    + normalizedCacheValue(catalogPattern)
                    + ':'
                    + normalizedCacheValue(schemaPattern)
                    + ':'
                    + normalizedCacheValue(tablePattern)
                    + ':'
                    + normalizeTypeKey(types),
                () ->
                    metadataQueryService.tables(
                        toDataSourceList(requiredSession.requireMetadataDataSources()),
                        catalogPattern,
                        schemaPattern,
                        tablePattern,
                        types),
                metadataCacheService));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult columns(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final String columnPattern) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return columns(
          session,
          request == null ? null : request.contextId(),
          catalogPattern,
          schemaPattern,
          tablePattern,
          columnPattern);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult columns(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final String columnPattern) {
    JdbcConnectionSession requiredSession = requireSession(session);
    // CHECKSTYLE.SUPPRESS: LineLength for +1 lines
    return withDriverContext(
        requiredSession,
        contextId,
        resolveMetadataContextCatalog(requiredSession, catalogPattern),
        (resolvedSession, resolvedContextId) ->
            requiredSession.cachedMetadata(
                "columns:"
                    + normalizedCacheValue(catalogPattern)
                    + ':'
                    + normalizedCacheValue(schemaPattern)
                    + ':'
                    + normalizedCacheValue(tablePattern)
                    + ':'
                    + normalizedCacheValue(columnPattern),
                () ->
                    metadataQueryService.columns(
                        toDataSourceList(requiredSession.requireMetadataDataSources()),
                        catalogPattern,
                        schemaPattern,
                        tablePattern,
                        columnPattern),
                metadataCacheService));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult primaryKeys(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalog,
      final String schema,
      final String table) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return primaryKeys(
          session, request == null ? null : request.contextId(), catalog, schema, table);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult primaryKeys(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalog,
      final String schema,
      final String table) {
    JdbcConnectionSession requiredSession = requireSession(session);
    // CHECKSTYLE.SUPPRESS: LineLength for +1 lines
    return withDriverContext(
        requiredSession,
        contextId,
        resolveMetadataContextCatalog(requiredSession, catalog),
        (resolvedSession, resolvedContextId) ->
            requiredSession.cachedMetadata(
                "primaryKeys:"
                    + normalizedCacheValue(catalog)
                    + ':'
                    + normalizedCacheValue(schema)
                    + ':'
                    + normalizedCacheValue(table),
                () ->
                    metadataQueryService.primaryKeys(
                        toDataSourceList(requiredSession.requireMetadataDataSources()),
                        catalog,
                        schema,
                        table),
                metadataCacheService));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult indexInfo(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalog,
      final String schema,
      final String table,
      final boolean unique,
      final boolean approximate) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return indexInfo(
          session,
          request == null ? null : request.contextId(),
          catalog,
          schema,
          table,
          unique,
          approximate);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult indexInfo(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalog,
      final String schema,
      final String table,
      final boolean unique,
      final boolean approximate) {
    JdbcConnectionSession requiredSession = requireSession(session);
    // CHECKSTYLE.SUPPRESS: LineLength for +1 lines
    return withDriverContext(
        requiredSession,
        contextId,
        resolveMetadataContextCatalog(requiredSession, catalog),
        (resolvedSession, resolvedContextId) ->
            requiredSession.cachedMetadata(
                "indexInfo:"
                    + normalizedCacheValue(catalog)
                    + ':'
                    + normalizedCacheValue(schema)
                    + ':'
                    + normalizedCacheValue(table)
                    + ':'
                    + unique
                    + ':'
                    + approximate,
                () ->
                    metadataQueryService.indexInfo(
                        toDataSourceList(requiredSession.requireMetadataDataSources()),
                        catalog,
                        schema,
                        table,
                        unique,
                        approximate),
                metadataCacheService));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult importedKeys(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalog,
      final String schema,
      final String table) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return importedKeys(
          session, request == null ? null : request.contextId(), catalog, schema, table);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult importedKeys(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalog,
      final String schema,
      final String table) {
    JdbcConnectionSession requiredSession = requireSession(session);
    // CHECKSTYLE.SUPPRESS: LineLength for +1 lines
    return withDriverContext(
        requiredSession,
        contextId,
        resolveMetadataContextCatalog(requiredSession, catalog),
        (resolvedSession, resolvedContextId) ->
            requiredSession.cachedMetadata(
                "importedKeys:"
                    + normalizedCacheValue(catalog)
                    + ':'
                    + normalizedCacheValue(schema)
                    + ':'
                    + normalizedCacheValue(table),
                () ->
                    metadataQueryService.importedKeys(
                        toDataSourceList(requiredSession.requireMetadataDataSources()),
                        catalog,
                        schema,
                        table),
                metadataCacheService));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult exportedKeys(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalog,
      final String schema,
      final String table) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return exportedKeys(
          session, request == null ? null : request.contextId(), catalog, schema, table);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult exportedKeys(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalog,
      final String schema,
      final String table) {
    JdbcConnectionSession requiredSession = requireSession(session);
    // CHECKSTYLE.SUPPRESS: LineLength for +1 lines
    return withDriverContext(
        requiredSession,
        contextId,
        resolveMetadataContextCatalog(requiredSession, catalog),
        (resolvedSession, resolvedContextId) ->
            requiredSession.cachedMetadata(
                "exportedKeys:"
                    + normalizedCacheValue(catalog)
                    + ':'
                    + normalizedCacheValue(schema)
                    + ':'
                    + normalizedCacheValue(table),
                () ->
                    metadataQueryService.exportedKeys(
                        toDataSourceList(requiredSession.requireMetadataDataSources()),
                        catalog,
                        schema,
                        table),
                metadataCacheService));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult typeInfo(
      final FederationJdbcDriverModels.DriverRequest request) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return typeInfo(session, request == null ? null : request.contextId());
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult typeInfo(
      final FederationJdbcDriverService.DriverSession session, final String contextId) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(
        requiredSession,
        contextId,
        requiredSession.selectedCatalogCode(),
        (resolvedSession, resolvedContextId) ->
            requiredSession.cachedMetadata(
                "typeInfo",
                () ->
                    metadataQueryService.typeInfo(
                        toDataSourceList(requiredSession.requireMetadataDataSources())),
                metadataCacheService));
  }

  @Override
  public long flushCache(final FederationJdbcDriverService.DriverSession session) {
    requireSession(session).metadataCache.clear();
    return metadataCacheService.flushAll();
  }

  @Override
  public FederationQueryModels.SqlQueryResult query(
      final FederationJdbcDriverModels.DriverRequest request,
      final FederationJdbcDriverModels.QueryRequest queryRequest) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return query(session, request == null ? null : request.contextId(), queryRequest);
    }
  }

  @Override
  public FederationQueryModels.SqlQueryResult query(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final FederationJdbcDriverModels.QueryRequest queryRequest) {
    JdbcConnectionSession requiredSession = requireSession(session);
    AuthorizedDataSource queryDataSource =
        requiredSession.requireQueryDataSource(
            queryRequest == null ? null : queryRequest.catalogCode());
    return withDriverContext(
        requiredSession,
        contextId,
        queryDataSource.dataSource().getCode(),
        (resolvedSession, resolvedContextId) -> {
          String sql = requireValue(queryRequest == null ? null : queryRequest.sql(), "SQL 不能为空");
          String defaultSchema =
              trimToNull(queryRequest == null ? null : queryRequest.defaultSchema());
          List<Object> parameters = queryRequest == null ? null : queryRequest.parameters();
          Integer maxRows = queryRequest == null ? null : queryRequest.maxRows();
          return sqlConsoleService.execute(
              queryDataSource.dataSource().getId(),
              new FederationQueryModels.SqlConsoleRequest(
                  queryDataSource.dataSource().getCode(), sql, defaultSchema, parameters, maxRows));
        });
  }

  @Override
  public FederationQueryModels.SqlUpdateResult executeUpdate(
      final FederationJdbcDriverModels.DriverRequest request,
      final FederationJdbcDriverModels.QueryRequest queryRequest) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return executeUpdate(session, request == null ? null : request.contextId(), queryRequest);
    }
  }

  @Override
  public FederationQueryModels.SqlUpdateResult executeUpdate(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final FederationJdbcDriverModels.QueryRequest queryRequest) {
    JdbcConnectionSession requiredSession = requireSession(session);
    AuthorizedDataSource dmlDataSource =
        requiredSession.requireDmlDataSource(
            queryRequest == null ? null : queryRequest.catalogCode());
    return withDriverContext(
        requiredSession,
        contextId,
        dmlDataSource.dataSource().getCode(),
        (resolvedSession, resolvedContextId) -> {
          String sql = requireValue(queryRequest == null ? null : queryRequest.sql(), "SQL 不能为空");
          String defaultSchema =
              trimToNull(queryRequest == null ? null : queryRequest.defaultSchema());
          List<Object> parameters = queryRequest == null ? null : queryRequest.parameters();
          return sqlConsoleService.executeUpdate(
              dmlDataSource.dataSource().getId(),
              new FederationQueryModels.SqlConsoleRequest(
                  dmlDataSource.dataSource().getCode(), sql, defaultSchema, parameters));
        });
  }

  @Override
  public FederationQueryModels.SqlUpdateResult executeDdl(
      final FederationJdbcDriverModels.DriverRequest request,
      final FederationJdbcDriverModels.QueryRequest queryRequest) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return executeDdl(session, request == null ? null : request.contextId(), queryRequest);
    }
  }

  @Override
  public FederationQueryModels.SqlUpdateResult executeDdl(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final FederationJdbcDriverModels.QueryRequest queryRequest) {
    JdbcConnectionSession requiredSession = requireSession(session);
    AuthorizedDataSource ddlDataSource =
        requiredSession.requireDdlDataSource(
            queryRequest == null ? null : queryRequest.catalogCode());
    return withDriverContext(
        requiredSession,
        contextId,
        ddlDataSource.dataSource().getCode(),
        (resolvedSession, resolvedContextId) -> {
          String sql = requireValue(queryRequest == null ? null : queryRequest.sql(), "SQL 不能为空");
          String defaultSchema =
              trimToNull(queryRequest == null ? null : queryRequest.defaultSchema());
          List<Object> parameters = queryRequest == null ? null : queryRequest.parameters();
          return sqlConsoleService.executeDdl(
              ddlDataSource.dataSource().getId(),
              new FederationQueryModels.SqlConsoleRequest(
                  ddlDataSource.dataSource().getCode(), sql, defaultSchema, parameters));
        });
  }

  private <T> T withDriverContext(
      final JdbcConnectionSession session,
      final String requestedContextId,
      final String catalogCode,
      final DriverAction<T> action) {
    // Public entry points already obtained an immutable, freshly authorized operation snapshot.
    JdbcConnectionSession requiredSession = session;
    SecurityContext previousContext = SecurityContextHolder.getContext();
    Object previousAuthorization =
        RequestContextHolder.getContext(
            RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, Object.class);
    try {
      SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
      securityContext.setAuthentication(requiredSession.authentication());
      SecurityContextHolder.setContext(securityContext);
      String contextId = trimToNull(requestedContextId);
      AuthorizationContext authorizationContext =
          authorizationContextService.calculate(
              requiredSession.tenantId(),
              requiredSession.userId(),
              contextId,
              buildAuthorizationAttributes(requiredSession, contextId, catalogCode));
      if (authorizationContext == null
          || !requiredSession.userId().equals(authorizationContext.getUserId())) {
        throw new AccessDeniedException("Invalid JDBC authorization context");
      }
      RequestContextHolder.setContext(
          RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorizationContext);
      return action.execute(requiredSession, contextId);
    } finally {
      if (previousAuthorization == null) {
        RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
      } else {
        RequestContextHolder.setContext(
            RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, previousAuthorization);
      }
      SecurityContextHolder.setContext(previousContext);
    }
  }

  @Override
  public FederationJdbcDriverService.DriverSession openSession(
      final FederationJdbcDriverModels.DriverRequest request) {
    // Do not normalize secrets or locally duplicate password/MFA policy.
    CredentialSession proof =
        credentials.open(
            new CredentialRequest(
                request == null ? null : request.loginSubject(),
                request == null ? null : request.password(),
                request == null ? null : request.oneTimeCode()));
    try {
      requireIdentity(proof.identity());
      String catalogCode = trimToNull(request == null ? null : request.catalogCode());
      Map<String, AuthorizedDataSource> sources =
          resolveAuthorizedDataSources(proof.identity().userId());
      requireGrants(sources, catalogCode);
      String tenantId =
          resolveTenantId(
              trimToNull(request == null ? null : request.tenantId()), proof.identity().userId());
      return new JdbcConnectionSession(
          this,
          proof,
          new AtomicBoolean(),
          trimToNull(request == null ? null : request.loginSubject()),
          catalogCode,
          sources,
          tenantId,
          proof.identity(),
          new ConcurrentHashMap<>());
    } catch (RuntimeException failure) {
      try {
        credentials.close(proof.handle());
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private static void requireIdentity(CredentialIdentity identity) {
    if (identity == null
        || identity.userId() == null
        || identity.userId().isBlank()
        || identity.authorizationVersion() < 0
        || identity.expiresAt() == null
        || !Instant.now().isBefore(identity.expiresAt())) {
      throw new CredentialAuthenticationException(Code.SESSION_INVALID);
    }
  }

  private static void requireGrants(Map<String, AuthorizedDataSource> sources, String catalogCode) {
    if (sources.isEmpty()
        || (catalogCode != null && !sources.containsKey(normalizeCatalogKey(catalogCode)))) {
      throw new AccessDeniedException("当前系统用户未被授权访问该数据源的 JDBC 连接");
    }
  }

  private String resolveTenantId(final String requestedTenantId, final String userId) {
    var memberships = tenantService.getTenantsByUserId(userId);
    if (requestedTenantId != null) {
      if (memberships.stream().noneMatch(tenant -> requestedTenantId.equals(tenant.tenantId()))) {
        throw new AccessDeniedException("JDBC tenant membership is required");
      }
      return requestedTenantId;
    }
    return memberships.stream()
        .sorted(
            Comparator.comparing(
                    NamedTenantVo::tenantName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                    NamedTenantVo::tenantId, Comparator.nullsLast(Comparator.naturalOrder())))
        .map(NamedTenantVo::tenantId)
        .filter(id -> id != null && !id.isBlank())
        .findFirst()
        .orElseThrow(() -> new AccessDeniedException("JDBC tenant membership is required"));
  }

  private static Authentication createAuthentication(final CredentialIdentity identity) {
    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
    if (identity.administrator()) {
      authorities.add(new SimpleGrantedAuthority("ROLE_Administrator"));
    }
    return new UsernamePasswordAuthenticationToken(identity.userId(), null, authorities);
  }

  private static Map<String, String> buildAuthorizationAttributes(
      final JdbcConnectionSession session, final String contextId, final String catalogCode) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("X-User-Id", session.userId());
    attributes.put("X-Tenant-Id", session.tenantId());
    if (contextId != null) {
      attributes.put("X-Context-Id", contextId);
    }
    if (catalogCode != null) {
      attributes.put("X-DNA-Catalog-Code", catalogCode);
    }
    return Map.copyOf(attributes);
  }

  private Map<String, AuthorizedDataSource> resolveAuthorizedDataSources(final String userId) {
    Map<String, FederationJdbcConnectionUser> grantsByDataSourceId =
        jdbcConnectionUserService.enabledGrants(userId).stream()
            .filter(grant -> trimToNull(grant.getCatalogId()) != null)
            .collect(
                java.util.stream.Collectors.toMap(
                    FederationJdbcConnectionUser::getCatalogId,
                    grant -> grant,
                    (left, right) -> left,
                    LinkedHashMap::new));
    if (grantsByDataSourceId.isEmpty()) {
      return Map.of();
    }
    Map<String, AuthorizedDataSource> authorizedDataSources = new LinkedHashMap<>();
    for (JdbcDataSourceDefinition dataSource : dataSourceService.listEnabledDefinitions()) {
      FederationJdbcConnectionUser grant =
          grantsByDataSourceId.get(dataSource == null ? null : dataSource.getId());
      String catalogCode = trimToNull(dataSource == null ? null : dataSource.getCode());
      if (grant == null || catalogCode == null) {
        continue;
      }
      authorizedDataSources.put(
          normalizeCatalogKey(catalogCode), new AuthorizedDataSource(dataSource, grant));
    }
    return Map.copyOf(authorizedDataSources);
  }

  private static List<JdbcDataSourceDefinition> toDataSourceList(
      final List<AuthorizedDataSource> sources) {
    return sources == null
        ? List.of()
        : sources.stream().map(AuthorizedDataSource::dataSource).toList();
  }

  private static String resolveMetadataContextCatalog(
      final JdbcConnectionSession session, final String catalog) {
    String normalizedCatalog = trimToNull(catalog);
    if (normalizedCatalog == null) {
      return session == null ? null : session.selectedCatalogCode();
    }
    return normalizedCatalog;
  }

  private static String normalizeCatalogKey(final String catalogCode) {
    return requireValue(trimToNull(catalogCode), "数据源编码不能为空").toLowerCase(Locale.ROOT);
  }

  private static String normalizedCacheValue(final String value) {
    String normalized = trimToNull(value);
    return normalized == null ? "*" : normalized;
  }

  private static String normalizeTypeKey(final List<String> types) {
    if (types == null || types.isEmpty()) {
      return "*";
    }
    return types.stream()
        .map(type -> trimToNull(type))
        .filter(Objects::nonNull)
        .toList()
        .toString();
  }

  private static String resolveImplementationVersion() {
    String version =
        trimToNull(FederationJdbcDriverServiceImpl.class.getPackage().getImplementationVersion());
    return version == null ? "dev" : version;
  }

  @FunctionalInterface
  private interface DriverAction<T> {
    T execute(JdbcConnectionSession session, String contextId);
  }

  private JdbcConnectionSession requireSession(
      final FederationJdbcDriverService.DriverSession session) {
    if (!(session instanceof JdbcConnectionSession current)
        || current.owner != this
        || current.closed.get()) {
      throw new CredentialAuthenticationException(Code.SESSION_INVALID);
    }
    CredentialIdentity identity = credentials.check(current.proof.handle());
    requireIdentity(identity);
    if (!current.userId.equals(identity.userId())
        || current.proof.identity().authorizationVersion() != identity.authorizationVersion()
        || !current.proof.identity().expiresAt().equals(identity.expiresAt())) {
      throw new CredentialAuthenticationException(Code.SESSION_INVALID);
    }
    Map<String, AuthorizedDataSource> sources = resolveAuthorizedDataSources(identity.userId());
    requireGrants(sources, current.selectedCatalogCode);
    resolveTenantId(current.tenantId, identity.userId());
    return new JdbcConnectionSession(
        this,
        current.proof,
        current.closed,
        current.loginSubject,
        current.selectedCatalogCode,
        sources,
        current.tenantId,
        identity,
        current.metadataCache);
  }

  private record AuthorizedDataSource(
      JdbcDataSourceDefinition dataSource, FederationJdbcConnectionUser grant) {}

  private static final class JdbcConnectionSession
      implements FederationJdbcDriverService.DriverSession {

    private final String loginSubject;

    private final String userId;

    private final String selectedCatalogCode;

    private final Map<String, AuthorizedDataSource> authorizedDataSources;

    private final String tenantId;

    private final Authentication authentication;

    private final FederationJdbcDriverServiceImpl owner;
    private final CredentialSession proof;
    private final AtomicBoolean closed;
    private final ConcurrentMap<String, FederationJdbcDriverModels.TabularResult> metadataCache;

    private JdbcConnectionSession(
        FederationJdbcDriverServiceImpl owner,
        CredentialSession proof,
        AtomicBoolean closed,
        String loginSubject,
        String selectedCatalogCode,
        Map<String, AuthorizedDataSource> sources,
        String tenantId,
        CredentialIdentity identity,
        ConcurrentMap<String, FederationJdbcDriverModels.TabularResult> metadataCache) {
      this.owner = owner;
      this.proof = proof;
      this.closed = closed;
      this.metadataCache = metadataCache;
      this.loginSubject = loginSubject;
      this.userId = identity.userId();
      this.selectedCatalogCode = selectedCatalogCode;
      this.authorizedDataSources = Map.copyOf(sources);
      this.tenantId = tenantId;
      this.authentication = createAuthentication(identity);
    }

    private FederationJdbcDriverModels.TabularResult cachedMetadata(
        final String key,
        final MetadataLoader loader,
        final FederationMetadataCacheService globalCache) {
      // Validate before every cache lookup; never reuse a result for a broader metadata grant set.
      List<String> ids =
          requireMetadataDataSources().stream().map(source -> source.dataSource().getId()).toList();
      String scopedKey = globalCache.scopedKey(ids, key);
      // Bound connection-local metadata; versioned keys fence invalidation and changed grant sets.
      if (metadataCache.size() >= 128) {
        metadataCache.clear();
      }
      return metadataCache.computeIfAbsent(
          scopedKey,
          ignored -> {
            var cached = globalCache.get(scopedKey);
            if (cached != null) {
              return cached;
            }
            var result = loader.load();
            globalCache.put(scopedKey, result);
            return result;
          });
    }

    private String loginSubject() {
      return loginSubject;
    }

    private String userId() {
      return userId;
    }

    private String selectedCatalogCode() {
      return selectedCatalogCode;
    }

    private String tenantId() {
      return tenantId;
    }

    private Authentication authentication() {
      return authentication;
    }

    private Optional<AuthorizedDataSource> findAuthorizedDataSource(final String catalogCode) {
      String normalizedCatalogCode = trimToNull(catalogCode);
      if (normalizedCatalogCode == null) {
        return Optional.empty();
      }
      return Optional.ofNullable(
          authorizedDataSources.get(normalizeCatalogKey(normalizedCatalogCode)));
    }

    private List<AuthorizedDataSource> requireMetadataDataSources() {
      List<AuthorizedDataSource> metadataDataSources =
          authorizedDataSources.values().stream()
              .filter(
                  source ->
                      source
                          .grant()
                          .getOperationPermissions()
                          .contains(FederationJdbcOperation.METADATA.name()))
              .sorted(
                  Comparator.comparing(
                      source -> source.dataSource().getCode(),
                      Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
              .toList();
      if (metadataDataSources.isEmpty()) {
        throw new AccessDeniedException(
            "当前 JDBC 连接用户未授权操作: " + FederationJdbcOperation.METADATA.name());
      }
      return metadataDataSources;
    }

    private AuthorizedDataSource requireQueryDataSource(final String requestedCatalogCode) {
      return requireOperationDataSource(requestedCatalogCode, FederationJdbcOperation.QUERY);
    }

    private AuthorizedDataSource requireDmlDataSource(final String requestedCatalogCode) {
      return requireOperationDataSource(requestedCatalogCode, FederationJdbcOperation.DML);
    }

    private AuthorizedDataSource requireDdlDataSource(final String requestedCatalogCode) {
      return requireOperationDataSource(requestedCatalogCode, FederationJdbcOperation.DDL);
    }

    private AuthorizedDataSource requireOperationDataSource(
        final String requestedCatalogCode, final FederationJdbcOperation operation) {
      String normalizedRequestedCatalogCode = trimToNull(requestedCatalogCode);
      if (normalizedRequestedCatalogCode != null) {
        AuthorizedDataSource authorizedDataSource =
            findAuthorizedDataSource(normalizedRequestedCatalogCode)
                .orElseThrow(() -> new AccessDeniedException("当前系统用户未被授权访问该数据源的 JDBC 连接"));
        if (!authorizedDataSource.grant().getOperationPermissions().contains(operation.name())) {
          throw new AccessDeniedException("当前 JDBC 连接用户未授权操作: " + operation.name());
        }
        return authorizedDataSource;
      }
      String normalizedSelectedCatalogCode = trimToNull(selectedCatalogCode);
      if (normalizedSelectedCatalogCode != null) {
        return requireOperationDataSource(normalizedSelectedCatalogCode, operation);
      }
      List<AuthorizedDataSource> candidates =
          authorizedDataSources.values().stream()
              .filter(source -> source.grant().getOperationPermissions().contains(operation.name()))
              .sorted(
                  Comparator.comparing(
                      source -> source.dataSource().getCode(),
                      Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
              .toList();
      if (candidates.isEmpty()) {
        throw new AccessDeniedException("当前 JDBC 连接用户未授权操作: " + operation.name());
      }
      return candidates.get(0);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        metadataCache.clear();
        owner.credentials.close(proof.handle());
      }
    }
  }

  @FunctionalInterface
  private interface MetadataLoader {
    FederationJdbcDriverModels.TabularResult load();
  }
}
