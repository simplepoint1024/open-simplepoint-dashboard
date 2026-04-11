package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

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
import org.simplepoint.plugin.dna.federation.service.support.FederationJdbcMetadataSupport;
import org.simplepoint.plugin.dna.federation.service.support.FederationMetadataCacheService;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.security.context.AuthorizationContextService;
import org.simplepoint.security.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * External DNA JDBC driver gateway service implementation.
 */
@Service
public class FederationJdbcDriverServiceImpl implements FederationJdbcDriverService {

  private static final Logger LOG = LoggerFactory.getLogger(FederationJdbcDriverServiceImpl.class);
  private static final String DATABASE_PRODUCT_NAME = "SimplePoint DNA Federation";

  private static final String NO_MATCH_CATALOG_PATTERN = "__simplepoint_no_match__";

  private final JdbcDataSourceDefinitionService dataSourceService;

  private final FederationJdbcConnectionUserService jdbcConnectionUserService;

  private final FederationSqlConsoleService sqlConsoleService;

  private final FederationJdbcMetadataSupport jdbcMetadataSupport;

  private final UsersService usersService;

  private final PasswordEncoder passwordEncoder;

  private final TenantService tenantService;

  private final AuthorizationContextService authorizationContextService;

  private final FederationMetadataCacheService metadataCacheService;

  /**
   * Creates a JDBC driver gateway service.
   *
   * @param dataSourceService datasource service
   * @param jdbcConnectionUserService jdbc grant service
   * @param sqlConsoleService sql console service
   * @param jdbcMetadataSupport metadata support
   * @param usersService users service
   * @param passwordEncoder password encoder
   * @param tenantService tenant service
   * @param authorizationContextService authorization context service
   * @param metadataCacheService global metadata cache service
   */
  public FederationJdbcDriverServiceImpl(
      final JdbcDataSourceDefinitionService dataSourceService,
      final FederationJdbcConnectionUserService jdbcConnectionUserService,
      final FederationSqlConsoleService sqlConsoleService,
      final FederationJdbcMetadataSupport jdbcMetadataSupport,
      final UsersService usersService,
      final PasswordEncoder passwordEncoder,
      final TenantService tenantService,
      final AuthorizationContextService authorizationContextService,
      final FederationMetadataCacheService metadataCacheService
  ) {
    this.dataSourceService = dataSourceService;
    this.jdbcConnectionUserService = jdbcConnectionUserService;
    this.sqlConsoleService = sqlConsoleService;
    this.jdbcMetadataSupport = jdbcMetadataSupport;
    this.usersService = usersService;
    this.passwordEncoder = passwordEncoder;
    this.tenantService = tenantService;
    this.authorizationContextService = authorizationContextService;
    this.metadataCacheService = metadataCacheService;
  }

  @Override
  public FederationJdbcDriverModels.PingResult ping(final FederationJdbcDriverModels.DriverRequest request) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return ping(session, request == null ? null : request.contextId());
    }
  }

  @Override
  public FederationJdbcDriverModels.PingResult ping(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(requiredSession, contextId, requiredSession.selectedCatalogCode(), (resolvedSession, resolvedContextId) ->
        new FederationJdbcDriverModels.PingResult(
            resolvedSession.selectedCatalogCode(),
            resolvedSession.tenantId(),
            resolvedContextId,
            resolvedSession.userId(),
            resolvedSession.loginSubject(),
            DATABASE_PRODUCT_NAME,
            resolveImplementationVersion(),
            null
        )
    );
  }

  @Override
  public FederationJdbcDriverModels.TabularResult catalogs(final FederationJdbcDriverModels.DriverRequest request) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return catalogs(session, request == null ? null : request.contextId());
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult catalogs(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(requiredSession, contextId, requiredSession.selectedCatalogCode(), (resolvedSession, resolvedContextId) ->
        requiredSession.cachedMetadata("catalogs", () -> buildCatalogsResult(requiredSession.requireMetadataDataSources()), metadataCacheService)
    );
  }

  @Override
  public FederationJdbcDriverModels.TabularResult schemas(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalogPattern,
      final String schemaPattern
  ) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return schemas(session, request == null ? null : request.contextId(), catalogPattern, schemaPattern);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult schemas(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalogPattern,
      final String schemaPattern
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(requiredSession, contextId, resolveMetadataContextCatalog(requiredSession, catalogPattern), (resolvedSession, resolvedContextId) ->
        requiredSession.cachedMetadata(
            "schemas:" + normalizedCacheValue(catalogPattern) + ':' + normalizedCacheValue(schemaPattern),
            () -> aggregateSchemas(requiredSession, catalogPattern, schemaPattern),
            metadataCacheService
        )
    );
  }

  @Override
  public FederationJdbcDriverModels.TabularResult tableTypes(final FederationJdbcDriverModels.DriverRequest request) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return tableTypes(session, request == null ? null : request.contextId());
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult tableTypes(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(requiredSession, contextId, requiredSession.selectedCatalogCode(), (resolvedSession, resolvedContextId) ->
        requiredSession.cachedMetadata("tableTypes", () -> deduplicateRows(mergeTabularResults(
            requiredSession.requireMetadataDataSources().stream()
                .map(source -> jdbcMetadataSupport.tableTypes(source.dataSource()))
                .toList()
        )), metadataCacheService)
    );
  }

  @Override
  public FederationJdbcDriverModels.TabularResult tables(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final List<String> types
  ) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return tables(session, request == null ? null : request.contextId(), catalogPattern, schemaPattern, tablePattern, types);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult tables(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final List<String> types
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(requiredSession, contextId, resolveMetadataContextCatalog(requiredSession, catalogPattern), (resolvedSession, resolvedContextId) ->
        requiredSession.cachedMetadata(
            "tables:" + normalizedCacheValue(catalogPattern) + ':' + normalizedCacheValue(schemaPattern) + ':'
                + normalizedCacheValue(tablePattern) + ':' + normalizeTypeKey(types),
            () -> aggregateTables(requiredSession, catalogPattern, schemaPattern, tablePattern, types),
            metadataCacheService
        )
    );
  }

  @Override
  public FederationJdbcDriverModels.TabularResult columns(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final String columnPattern
  ) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return columns(session, request == null ? null : request.contextId(), catalogPattern, schemaPattern, tablePattern, columnPattern);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult columns(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final String columnPattern
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(requiredSession, contextId, resolveMetadataContextCatalog(requiredSession, catalogPattern), (resolvedSession, resolvedContextId) ->
        requiredSession.cachedMetadata(
            "columns:" + normalizedCacheValue(catalogPattern) + ':' + normalizedCacheValue(schemaPattern) + ':'
                + normalizedCacheValue(tablePattern) + ':' + normalizedCacheValue(columnPattern),
            () -> aggregateColumns(requiredSession, catalogPattern, schemaPattern, tablePattern, columnPattern),
            metadataCacheService
        )
    );
  }

  @Override
  public FederationJdbcDriverModels.TabularResult primaryKeys(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalog,
      final String schema,
      final String table
  ) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return primaryKeys(session, request == null ? null : request.contextId(), catalog, schema, table);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult primaryKeys(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalog,
      final String schema,
      final String table
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(requiredSession, contextId, resolveMetadataContextCatalog(requiredSession, catalog), (resolvedSession, resolvedContextId) ->
        requiredSession.cachedMetadata(
            "primaryKeys:" + normalizedCacheValue(catalog) + ':' + normalizedCacheValue(schema) + ':' + normalizedCacheValue(table),
            () -> aggregateTableMetadata(requiredSession, catalog,
                ds -> jdbcMetadataSupport.primaryKeys(ds, catalog, schema, table)),
            metadataCacheService
        )
    );
  }

  @Override
  public FederationJdbcDriverModels.TabularResult indexInfo(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalog,
      final String schema,
      final String table,
      final boolean unique,
      final boolean approximate
  ) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return indexInfo(session, request == null ? null : request.contextId(), catalog, schema, table, unique, approximate);
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
      final boolean approximate
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(requiredSession, contextId, resolveMetadataContextCatalog(requiredSession, catalog), (resolvedSession, resolvedContextId) ->
        requiredSession.cachedMetadata(
            "indexInfo:" + normalizedCacheValue(catalog) + ':' + normalizedCacheValue(schema) + ':'
                + normalizedCacheValue(table) + ':' + unique + ':' + approximate,
            () -> aggregateTableMetadata(requiredSession, catalog,
                ds -> jdbcMetadataSupport.indexInfo(ds, catalog, schema, table, unique, approximate)),
            metadataCacheService
        )
    );
  }

  @Override
  public FederationJdbcDriverModels.TabularResult importedKeys(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalog,
      final String schema,
      final String table
  ) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return importedKeys(session, request == null ? null : request.contextId(), catalog, schema, table);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult importedKeys(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalog,
      final String schema,
      final String table
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(requiredSession, contextId, resolveMetadataContextCatalog(requiredSession, catalog), (resolvedSession, resolvedContextId) ->
        requiredSession.cachedMetadata(
            "importedKeys:" + normalizedCacheValue(catalog) + ':' + normalizedCacheValue(schema) + ':' + normalizedCacheValue(table),
            () -> aggregateTableMetadata(requiredSession, catalog,
                ds -> jdbcMetadataSupport.importedKeys(ds, catalog, schema, table)),
            metadataCacheService
        )
    );
  }

  @Override
  public FederationJdbcDriverModels.TabularResult exportedKeys(
      final FederationJdbcDriverModels.DriverRequest request,
      final String catalog,
      final String schema,
      final String table
  ) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return exportedKeys(session, request == null ? null : request.contextId(), catalog, schema, table);
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult exportedKeys(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final String catalog,
      final String schema,
      final String table
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(requiredSession, contextId, resolveMetadataContextCatalog(requiredSession, catalog), (resolvedSession, resolvedContextId) ->
        requiredSession.cachedMetadata(
            "exportedKeys:" + normalizedCacheValue(catalog) + ':' + normalizedCacheValue(schema) + ':' + normalizedCacheValue(table),
            () -> aggregateTableMetadata(requiredSession, catalog,
                ds -> jdbcMetadataSupport.exportedKeys(ds, catalog, schema, table)),
            metadataCacheService
        )
    );
  }

  @Override
  public FederationJdbcDriverModels.TabularResult typeInfo(final FederationJdbcDriverModels.DriverRequest request) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return typeInfo(session, request == null ? null : request.contextId());
    }
  }

  @Override
  public FederationJdbcDriverModels.TabularResult typeInfo(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    return withDriverContext(requiredSession, contextId, requiredSession.selectedCatalogCode(), (resolvedSession, resolvedContextId) ->
        requiredSession.cachedMetadata("typeInfo", () -> deduplicateRows(mergeTabularResults(
            requiredSession.requireMetadataDataSources().stream()
                .map(source -> jdbcMetadataSupport.typeInfo(source.dataSource()))
                .toList()
        )), metadataCacheService)
    );
  }

  @Override
  public long flushCache(final FederationJdbcDriverService.DriverSession session) {
    JdbcConnectionSession requiredSession = requireSession(session);
    requiredSession.clearMetadataCache();
    return metadataCacheService.flushAll();
  }

  @Override
  public FederationQueryModels.SqlQueryResult query(
      final FederationJdbcDriverModels.DriverRequest request,
      final FederationJdbcDriverModels.QueryRequest queryRequest
  ) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return query(session, request == null ? null : request.contextId(), queryRequest);
    }
  }

  @Override
  public FederationQueryModels.SqlQueryResult query(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final FederationJdbcDriverModels.QueryRequest queryRequest
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    AuthorizedDataSource queryDataSource = requiredSession.requireQueryDataSource(
        queryRequest == null ? null : queryRequest.catalogCode()
    );
    return withDriverContext(requiredSession, contextId, queryDataSource.dataSource().getCode(), (resolvedSession, resolvedContextId) -> {
      String sql = requireValue(queryRequest == null ? null : queryRequest.sql(), "SQL 不能为空");
      String defaultSchema = trimToNull(queryRequest == null ? null : queryRequest.defaultSchema());
      return sqlConsoleService.execute(queryDataSource.dataSource().getId(), new FederationQueryModels.SqlConsoleRequest(
          queryDataSource.dataSource().getCode(),
          sql,
          defaultSchema
      ));
    });
  }

  @Override
  public FederationQueryModels.SqlUpdateResult executeUpdate(
      final FederationJdbcDriverModels.DriverRequest request,
      final FederationJdbcDriverModels.QueryRequest queryRequest
  ) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return executeUpdate(session, request == null ? null : request.contextId(), queryRequest);
    }
  }

  @Override
  public FederationQueryModels.SqlUpdateResult executeUpdate(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final FederationJdbcDriverModels.QueryRequest queryRequest
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    AuthorizedDataSource dmlDataSource = requiredSession.requireDmlDataSource(
        queryRequest == null ? null : queryRequest.catalogCode()
    );
    return withDriverContext(requiredSession, contextId, dmlDataSource.dataSource().getCode(), (resolvedSession, resolvedContextId) -> {
      String sql = requireValue(queryRequest == null ? null : queryRequest.sql(), "SQL 不能为空");
      String defaultSchema = trimToNull(queryRequest == null ? null : queryRequest.defaultSchema());
      return sqlConsoleService.executeUpdate(dmlDataSource.dataSource().getId(), new FederationQueryModels.SqlConsoleRequest(
          dmlDataSource.dataSource().getCode(),
          sql,
          defaultSchema
      ));
    });
  }

  @Override
  public FederationQueryModels.SqlUpdateResult executeDdl(
      final FederationJdbcDriverModels.DriverRequest request,
      final FederationJdbcDriverModels.QueryRequest queryRequest
  ) {
    try (FederationJdbcDriverService.DriverSession session = openSession(request)) {
      return executeDdl(session, request == null ? null : request.contextId(), queryRequest);
    }
  }

  @Override
  public FederationQueryModels.SqlUpdateResult executeDdl(
      final FederationJdbcDriverService.DriverSession session,
      final String contextId,
      final FederationJdbcDriverModels.QueryRequest queryRequest
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    AuthorizedDataSource ddlDataSource = requiredSession.requireDdlDataSource(
        queryRequest == null ? null : queryRequest.catalogCode()
    );
    return withDriverContext(requiredSession, contextId, ddlDataSource.dataSource().getCode(), (resolvedSession, resolvedContextId) -> {
      String sql = requireValue(queryRequest == null ? null : queryRequest.sql(), "SQL 不能为空");
      String defaultSchema = trimToNull(queryRequest == null ? null : queryRequest.defaultSchema());
      return sqlConsoleService.executeDdl(ddlDataSource.dataSource().getId(), new FederationQueryModels.SqlConsoleRequest(
          ddlDataSource.dataSource().getCode(),
          sql,
          defaultSchema
      ));
    });
  }

  private <T> T withDriverContext(
      final JdbcConnectionSession session,
      final String requestedContextId,
      final String catalogCode,
      final DriverAction<T> action
  ) {
    JdbcConnectionSession requiredSession = requireSession(session);
    SecurityContext previousContext = SecurityContextHolder.getContext();
    try {
      SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
      securityContext.setAuthentication(requiredSession.authentication());
      SecurityContextHolder.setContext(securityContext);
      String contextId = trimToNull(requestedContextId);
      AuthorizationContext authorizationContext = requiredSession.resolveAuthorizationContext(
          contextId,
          catalogCode,
          resolvedContextId -> authorizationContextService.calculate(
              requiredSession.tenantId(),
              requiredSession.userId(),
              resolvedContextId,
              buildAuthorizationAttributes(requiredSession, resolvedContextId, catalogCode)
          )
      );
      RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorizationContext);
      return action.execute(requiredSession, contextId);
    } finally {
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
      SecurityContextHolder.setContext(previousContext);
    }
  }

  @Override
  public FederationJdbcDriverService.DriverSession openSession(final FederationJdbcDriverModels.DriverRequest request) {
    String loginSubject = requireValue(request == null ? null : request.loginSubject(), "JDBC 登录账号不能为空");
    String password = requireValue(request == null ? null : request.password(), "JDBC 登录密码不能为空");
    String catalogCode = trimToNull(request == null ? null : request.catalogCode());
    User user = requireAuthenticatedUser(loginSubject, password);
    Map<String, AuthorizedDataSource> authorizedDataSources = resolveAuthorizedDataSources(user.getId());
    if (authorizedDataSources.isEmpty()) {
      throw new AccessDeniedException("当前系统用户未被授权访问任何 DNA JDBC 数据源");
    }
    if (catalogCode != null && !authorizedDataSources.containsKey(normalizeCatalogKey(catalogCode))) {
      dataSourceService.findActiveByCode(catalogCode)
          .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
          .orElseThrow(() -> new IllegalArgumentException("数据源不存在或未启用: " + catalogCode));
      throw new AccessDeniedException("当前系统用户未被授权访问该数据源的 JDBC 连接");
    }
    String tenantId = resolveTenantId(trimToNull(request == null ? null : request.tenantId()), user.getId());
    return new JdbcConnectionSession(
        loginSubject,
        user.getId(),
        catalogCode,
        authorizedDataSources,
        tenantId,
        createAuthentication(user)
    );
  }

  private User requireAuthenticatedUser(final String loginSubject, final String password) {
    User user = usersService.loadUserByPhoneOrEmail(loginSubject);
    if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
      throw new AccessDeniedException("JDBC 登录失败：系统用户不存在或已禁用");
    }
    String encodedPassword = trimToNull(user.getPassword());
    if (encodedPassword == null || !passwordEncoder.matches(password, encodedPassword)) {
      throw new AccessDeniedException("JDBC 登录失败：账号或密码错误");
    }
    return user;
  }

  private String resolveTenantId(final String requestedTenantId, final String userId) {
    if (requestedTenantId != null) {
      return requestedTenantId;
    }
    return tenantService.getTenantsByUserId(userId).stream()
        .sorted(
            Comparator.comparing(NamedTenantVo::tenantName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(NamedTenantVo::tenantId, Comparator.nullsLast(Comparator.naturalOrder()))
        )
        .map(NamedTenantVo::tenantId)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse("default");
  }

  private Authentication createAuthentication(final User user) {
    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
    if (Boolean.TRUE.equals(user.superAdmin())) {
      authorities.add(new SimpleGrantedAuthority("ROLE_Administrator"));
    }
    return new UsernamePasswordAuthenticationToken(user.getId(), null, authorities);
  }

  private static Map<String, String> buildAuthorizationAttributes(
      final JdbcConnectionSession session,
      final String contextId,
      final String catalogCode
  ) {
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
    Map<String, FederationJdbcConnectionUser> grantsByDataSourceId = jdbcConnectionUserService.enabledGrants(userId).stream()
        .filter(grant -> trimToNull(grant.getCatalogId()) != null)
        .collect(java.util.stream.Collectors.toMap(
            FederationJdbcConnectionUser::getCatalogId,
            grant -> grant,
            (left, right) -> left,
            LinkedHashMap::new
        ));
    if (grantsByDataSourceId.isEmpty()) {
      return Map.of();
    }
    Map<String, AuthorizedDataSource> authorizedDataSources = new LinkedHashMap<>();
    for (JdbcDataSourceDefinition dataSource : dataSourceService.listEnabledDefinitions()) {
      FederationJdbcConnectionUser grant = grantsByDataSourceId.get(dataSource == null ? null : dataSource.getId());
      String catalogCode = trimToNull(dataSource == null ? null : dataSource.getCode());
      if (grant == null || catalogCode == null) {
        continue;
      }
      authorizedDataSources.put(normalizeCatalogKey(catalogCode), new AuthorizedDataSource(dataSource, grant));
    }
    return Map.copyOf(authorizedDataSources);
  }

  private FederationJdbcDriverModels.TabularResult aggregateSchemas(
      final JdbcConnectionSession session,
      final String catalogPattern,
      final String schemaPattern
  ) {
    List<AuthorizedDataSource> metadataSources = session.requireMetadataDataSources();
    List<AuthorizedDataSource> filteredSources = filterByCatalogPattern(metadataSources, catalogPattern);
    if (filteredSources.isEmpty()) {
      return jdbcMetadataSupport.schemas(metadataSources.get(0).dataSource(), NO_MATCH_CATALOG_PATTERN, schemaPattern);
    }
    return safeAggregateFromSources(filteredSources,
        ds -> jdbcMetadataSupport.schemas(ds, catalogPattern, schemaPattern));
  }

  private FederationJdbcDriverModels.TabularResult aggregateTables(
      final JdbcConnectionSession session,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final List<String> types
  ) {
    List<AuthorizedDataSource> metadataSources = session.requireMetadataDataSources();
    List<AuthorizedDataSource> filteredSources = filterByCatalogPattern(metadataSources, catalogPattern);
    if (filteredSources.isEmpty()) {
      return jdbcMetadataSupport.tables(
          metadataSources.get(0).dataSource(),
          NO_MATCH_CATALOG_PATTERN,
          schemaPattern,
          tablePattern,
          types
      );
    }
    return safeAggregateFromSources(filteredSources,
        ds -> jdbcMetadataSupport.tables(ds, catalogPattern, schemaPattern, tablePattern, types));
  }

  private FederationJdbcDriverModels.TabularResult aggregateColumns(
      final JdbcConnectionSession session,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final String columnPattern
  ) {
    List<AuthorizedDataSource> metadataSources = session.requireMetadataDataSources();
    List<AuthorizedDataSource> filteredSources = filterByCatalogPattern(metadataSources, catalogPattern);
    if (filteredSources.isEmpty()) {
      return jdbcMetadataSupport.columns(
          metadataSources.get(0).dataSource(),
          NO_MATCH_CATALOG_PATTERN,
          schemaPattern,
          tablePattern,
          columnPattern
      );
    }
    return safeAggregateFromSources(filteredSources,
        ds -> jdbcMetadataSupport.columns(ds, catalogPattern, schemaPattern, tablePattern, columnPattern));
  }

  private FederationJdbcDriverModels.TabularResult aggregateTableMetadata(
      final JdbcConnectionSession session,
      final String catalog,
      final DataSourceMetadataAction action
  ) {
    List<AuthorizedDataSource> metadataSources = session.requireMetadataDataSources();
    List<AuthorizedDataSource> filteredSources = filterByCatalogPattern(metadataSources, catalog);
    if (filteredSources.isEmpty()) {
      return new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    }
    return safeAggregateFromSources(filteredSources, action::apply);
  }

  @FunctionalInterface
  private interface DataSourceMetadataAction {
    FederationJdbcDriverModels.TabularResult apply(JdbcDataSourceDefinition dataSource);
  }

  /**
   * 安全地从单个数据源收集元数据，失败时记录警告并返回空结果。
   */
  private static FederationJdbcDriverModels.TabularResult safeCollect(
      final AuthorizedDataSource source,
      final DataSourceMetadataAction action
  ) {
    try {
      return action.apply(source.dataSource());
    } catch (Exception ex) {
      LOG.warn("数据源 [{}] 元数据查询失败，已跳过: {}",
          source.dataSource().getCode(), ex.getMessage(), ex);
      return null;
    }
  }

  /**
   * 对多个数据源并行执行元数据操作并合并结果，单个数据源失败不影响整体。
   */
  private FederationJdbcDriverModels.TabularResult safeAggregateFromSources(
      final List<AuthorizedDataSource> sources,
      final DataSourceMetadataAction action
  ) {
    if (sources.size() == 1) {
      FederationJdbcDriverModels.TabularResult single = safeCollect(sources.get(0), action);
      return single != null ? single : new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    }
    return mergeTabularResults(sources.parallelStream()
        .map(source -> safeCollect(source, action))
        .filter(Objects::nonNull)
        .toList());
  }

  private static FederationJdbcDriverModels.TabularResult buildCatalogsResult(
      final List<AuthorizedDataSource> dataSources
  ) {
    return new FederationJdbcDriverModels.TabularResult(
        List.of(new FederationJdbcDriverModels.JdbcColumn("TABLE_CAT", "VARCHAR", java.sql.Types.VARCHAR)),
        (dataSources == null ? List.<AuthorizedDataSource>of() : dataSources).stream()
            .map(AuthorizedDataSource::dataSource)
            .map(JdbcDataSourceDefinition::getCode)
            .filter(Objects::nonNull)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .<List<Object>>map(code -> List.of(code))
            .toList()
    );
  }

  private static FederationJdbcDriverModels.TabularResult mergeTabularResults(
      final List<FederationJdbcDriverModels.TabularResult> results
  ) {
    LinkedHashMap<String, FederationJdbcDriverModels.JdbcColumn> mergedColumns = new LinkedHashMap<>();
    List<Map<String, Object>> mappedRows = new ArrayList<>();
    for (FederationJdbcDriverModels.TabularResult result : results == null ? List.<FederationJdbcDriverModels.TabularResult>of() : results) {
      if (result == null) {
        continue;
      }
      List<FederationJdbcDriverModels.JdbcColumn> columns = result.columns();
      for (FederationJdbcDriverModels.JdbcColumn column : columns) {
        if (column != null && trimToNull(column.name()) != null) {
          mergedColumns.putIfAbsent(column.name(), column);
        }
      }
      for (List<Object> row : result.rows()) {
        Map<String, Object> rowValues = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
          FederationJdbcDriverModels.JdbcColumn column = columns.get(index);
          if (column == null || trimToNull(column.name()) == null) {
            continue;
          }
          rowValues.put(column.name(), row != null && index < row.size() ? row.get(index) : null);
        }
        mappedRows.add(rowValues);
      }
    }
    List<FederationJdbcDriverModels.JdbcColumn> columns = List.copyOf(mergedColumns.values());
    List<List<Object>> rows = mappedRows.stream()
        .map(row -> columns.stream()
            .map(column -> row.get(column.name()))
            .toList())
        .toList();
    return new FederationJdbcDriverModels.TabularResult(columns, rows);
  }

  private static FederationJdbcDriverModels.TabularResult deduplicateRows(
      final FederationJdbcDriverModels.TabularResult result
  ) {
    if (result == null || result.rows().isEmpty()) {
      return result == null ? new FederationJdbcDriverModels.TabularResult(List.of(), List.of()) : result;
    }
    return new FederationJdbcDriverModels.TabularResult(result.columns(), result.rows().stream().distinct().toList());
  }

  private static List<AuthorizedDataSource> filterByCatalogPattern(
      final List<AuthorizedDataSource> dataSources,
      final String catalog
  ) {
    String normalizedCatalog = trimToNull(catalog);
    if (normalizedCatalog == null) {
      return dataSources == null ? List.of() : dataSources;
    }
    return (dataSources == null ? List.<AuthorizedDataSource>of() : dataSources).stream()
        .filter(source -> normalizedCatalog.equalsIgnoreCase(source.dataSource().getCode()))
        .toList();
  }

  private static String resolveMetadataContextCatalog(
      final JdbcConnectionSession session,
      final String catalog
  ) {
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
    String version = trimToNull(FederationJdbcDriverServiceImpl.class.getPackage().getImplementationVersion());
    return version == null ? "dev" : version;
  }

  @FunctionalInterface
  private interface DriverAction<T> {
    T execute(JdbcConnectionSession session, String contextId);
  }

  private static JdbcConnectionSession requireSession(final FederationJdbcDriverService.DriverSession session) {
    if (!(session instanceof JdbcConnectionSession jdbcConnectionSession)) {
      throw new IllegalStateException("DNA JDBC 会话尚未建立");
    }
    return jdbcConnectionSession;
  }

  private record AuthorizedDataSource(
      JdbcDataSourceDefinition dataSource,
      FederationJdbcConnectionUser grant
  ) {
  }

  private static final class JdbcConnectionSession implements FederationJdbcDriverService.DriverSession {

    private final String loginSubject;

    private final String userId;

    private final String selectedCatalogCode;

    private final Map<String, AuthorizedDataSource> authorizedDataSources;

    private final String tenantId;

    private final Authentication authentication;

    private final ConcurrentMap<String, AuthorizationContext> authorizationContexts;

    private final ConcurrentMap<String, FederationJdbcDriverModels.TabularResult> metadataCache;

    private JdbcConnectionSession(
        final String loginSubject,
        final String userId,
        final String selectedCatalogCode,
        final Map<String, AuthorizedDataSource> authorizedDataSources,
        final String tenantId,
        final Authentication authentication
    ) {
      this.loginSubject = loginSubject;
      this.userId = userId;
      this.selectedCatalogCode = trimToNull(selectedCatalogCode);
      this.authorizedDataSources = authorizedDataSources == null ? Map.of() : Map.copyOf(authorizedDataSources);
      this.tenantId = tenantId;
      this.authentication = authentication;
      this.authorizationContexts = new ConcurrentHashMap<>();
      this.metadataCache = new ConcurrentHashMap<>();
    }

    private AuthorizationContext resolveAuthorizationContext(
        final String contextId,
        final String catalogCode,
        final AuthorizationContextLoader loader
    ) {
      String normalizedContextId = trimToNull(contextId);
      String normalizedCatalogCode = trimToNull(catalogCode);
      String cacheKey = (normalizedContextId == null ? "" : normalizedContextId)
          + "|"
          + (normalizedCatalogCode == null ? "" : normalizedCatalogCode);
      return authorizationContexts.computeIfAbsent(cacheKey, ignored -> loader.load(normalizedContextId));
    }

    private FederationJdbcDriverModels.TabularResult cachedMetadata(
        final String key,
        final MetadataLoader loader,
        final FederationMetadataCacheService globalCache
    ) {
      return metadataCache.computeIfAbsent(key, ignored -> {
        FederationJdbcDriverModels.TabularResult cached = globalCache.get(key);
        if (cached != null) {
          return cached;
        }
        FederationJdbcDriverModels.TabularResult result = loader.load();
        globalCache.put(key, result);
        return result;
      });
    }

    private void clearMetadataCache() {
      metadataCache.clear();
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
      return Optional.ofNullable(authorizedDataSources.get(normalizeCatalogKey(normalizedCatalogCode)));
    }

    private List<AuthorizedDataSource> requireMetadataDataSources() {
      List<AuthorizedDataSource> metadataDataSources = authorizedDataSources.values().stream()
          .filter(source -> source.grant().getOperationPermissions().contains(FederationJdbcOperation.METADATA.name()))
          .sorted(Comparator.comparing(
              source -> source.dataSource().getCode(),
              Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
          ))
          .toList();
      if (metadataDataSources.isEmpty()) {
        throw new AccessDeniedException("当前 JDBC 连接用户未授权操作: " + FederationJdbcOperation.METADATA.name());
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
        final String requestedCatalogCode,
        final FederationJdbcOperation operation
    ) {
      String normalizedRequestedCatalogCode = trimToNull(requestedCatalogCode);
      if (normalizedRequestedCatalogCode != null) {
        AuthorizedDataSource authorizedDataSource = findAuthorizedDataSource(normalizedRequestedCatalogCode)
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
      List<AuthorizedDataSource> candidates = authorizedDataSources.values().stream()
          .filter(source -> source.grant().getOperationPermissions().contains(operation.name()))
          .sorted(Comparator.comparing(
              source -> source.dataSource().getCode(),
              Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
          ))
          .toList();
      if (candidates.isEmpty()) {
        throw new AccessDeniedException("当前 JDBC 连接用户未授权操作: " + operation.name());
      }
      return candidates.get(0);
    }

    @Override
    public void close() {
      metadataCache.clear();
      authorizationContexts.clear();
    }
  }

  @FunctionalInterface
  private interface AuthorizationContextLoader {
    AuthorizationContext load(String contextId);
  }

  @FunctionalInterface
  private interface MetadataLoader {
    FederationJdbcDriverModels.TabularResult load();
  }
}
