package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationJdbcConnectionUser;
import org.simplepoint.plugin.dna.federation.api.service.FederationJdbcConnectionUserService;
import org.simplepoint.plugin.dna.federation.api.service.FederationJdbcDriverService;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.simplepoint.plugin.dna.federation.service.support.FederationJdbcMetadataSupport;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException.Code;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialIdentity;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialRequest;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialSession;
import org.simplepoint.plugin.rbac.core.api.service.CredentialAuthenticationService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.plugin.rbac.tenant.api.vo.TenantContextType;
import org.simplepoint.security.context.AuthorizationContextService;

@ExtendWith(MockitoExtension.class)
class FederationJdbcDriverServiceImplTest {

  @Mock private JdbcDataSourceDefinitionService dataSourceService;

  @Mock private FederationJdbcConnectionUserService jdbcConnectionUserService;

  @Mock private FederationSqlConsoleService sqlConsoleService;

  @Mock private FederationJdbcMetadataSupport jdbcMetadataSupport;

  @Mock private CredentialAuthenticationService credentials;

  @Mock private TenantDirectory tenantService;

  @Mock private AuthorizationContextService authorizationContextService;

  @Test
  void reusedSocketSessionShouldRefreshIdentityGrantsAndAuthorizationEveryOperation() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    FederationJdbcConnectionUser grant = enabledGrant("grant-1", "ds-1");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of(grant));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(dataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", "ds1", "tenant-a", "ctx-1"))) {
      FederationJdbcDriverModels.PingResult first = service.ping(session, "ctx-1");
      FederationJdbcDriverModels.PingResult second = service.ping(session, "ctx-1");

      assertThat(first.catalogCode()).isEqualTo("ds1");
      assertThat(second.userId()).isEqualTo("user-1");
    }

    verify(credentials).open(new CredentialRequest("alice@example.com", "secret", null));
    verify(credentials, times(2)).check(HANDLE);
    verify(credentials).close(HANDLE);
    verify(jdbcConnectionUserService, times(3)).enabledGrants("user-1");
    verify(dataSourceService, times(3)).listEnabledDefinitions();
    verify(dataSourceService, never()).findActiveByCode(anyString());
    verify(authorizationContextService, times(2))
        .calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any());
    verifyNoInteractions(sqlConsoleService, jdbcMetadataSupport);
  }

  @Test
  void openSessionWithoutCatalogShouldExposeAllAuthorizedCatalogs() {
    JdbcDataSourceDefinition firstDataSource = enabledDataSource("ds-1", "ds1");
    JdbcDataSourceDefinition secondDataSource = enabledDataSource("ds-2", "ds2");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1"))
        .thenReturn(List.of(enabledGrant("grant-1", "ds-1"), enabledGrant("grant-2", "ds-2")));
    when(dataSourceService.listEnabledDefinitions())
        .thenReturn(List.of(firstDataSource, secondDataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", null, "tenant-a", "ctx-1"))) {
      FederationJdbcDriverModels.PingResult ping = service.ping(session, "ctx-1");
      FederationJdbcDriverModels.TabularResult catalogs = service.catalogs(session, "ctx-1");

      assertThat(ping.catalogCode()).isNull();
      assertThat(catalogs.rows()).containsExactly(List.of("ds1"), List.of("ds2"));
    }

    verifyNoInteractions(jdbcMetadataSupport, sqlConsoleService);
  }

  @Test
  void queryShouldUseRequestedCatalogFromQueryPayloadWhenSessionHasNoDefaultCatalog() {
    JdbcDataSourceDefinition firstDataSource = enabledDataSource("ds-1", "ds1");
    JdbcDataSourceDefinition secondDataSource = enabledDataSource("ds-2", "ds2");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    final FederationQueryModels.SqlQueryResult queryResult =
        new FederationQueryModels.SqlQueryResult(
            "ds1",
            "policy-demo",
            200,
            15_000,
            true,
            false,
            List.of("ds1"),
            List.of(new FederationQueryModels.SqlColumn("id", "INTEGER")),
            List.of(List.of(1)),
            false,
            1,
            8,
            "JdbcTableScan(table=[[ds1, PUBLIC, ORDERS]])",
            List.of(),
            "命中数据源: ds1");
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1"))
        .thenReturn(List.of(enabledGrant("grant-1", "ds-1"), enabledGrant("grant-2", "ds-2")));
    when(dataSourceService.listEnabledDefinitions())
        .thenReturn(List.of(firstDataSource, secondDataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(sqlConsoleService.execute(eq("ds-1"), any(FederationQueryModels.SqlConsoleRequest.class)))
        .thenReturn(queryResult);
    FederationJdbcDriverServiceImpl service = service();

    final FederationQueryModels.SqlQueryResult response =
        service.query(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", null, "tenant-a", "ctx-1"),
            new FederationJdbcDriverModels.QueryRequest("select id from orders", "public", "ds1"));

    assertThat(response.catalogCode()).isEqualTo("ds1");
    verify(sqlConsoleService)
        .execute(
            eq("ds-1"),
            argThat(
                (FederationQueryModels.SqlConsoleRequest request) ->
                    "ds1".equals(request.catalogCode())
                        && "select id from orders".equals(request.sql())
                        && "public".equals(request.defaultSchema())));
  }

  @Test
  void tablesShouldOnlyLoadRequestedCatalogMetadata() {
    JdbcDataSourceDefinition firstDataSource = enabledDataSource("ds-1", "ds1");
    JdbcDataSourceDefinition secondDataSource = enabledDataSource("ds-2", "ds2");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    final FederationJdbcDriverModels.TabularResult tableResult =
        new FederationJdbcDriverModels.TabularResult(
            List.of(
                new FederationJdbcDriverModels.JdbcColumn(
                    "TABLE_CAT", "VARCHAR", java.sql.Types.VARCHAR),
                new FederationJdbcDriverModels.JdbcColumn(
                    "TABLE_SCHEM", "VARCHAR", java.sql.Types.VARCHAR),
                new FederationJdbcDriverModels.JdbcColumn(
                    "TABLE_NAME", "VARCHAR", java.sql.Types.VARCHAR)),
            List.of(List.of("ds1", "reporting", "orders")));
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1"))
        .thenReturn(List.of(enabledGrant("grant-1", "ds-1"), enabledGrant("grant-2", "ds-2")));
    when(dataSourceService.listEnabledDefinitions())
        .thenReturn(List.of(firstDataSource, secondDataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.tables(firstDataSource, "ds1", "reporting", "%", List.of("TABLE")))
        .thenReturn(tableResult);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", null, "tenant-a", "ctx-1"))) {
      FederationJdbcDriverModels.TabularResult response =
          service.tables(session, "ctx-1", "ds1", "reporting", "%", List.of("TABLE"));

      assertThat(response.rows()).containsExactly(List.of("ds1", "reporting", "orders"));
    }

    verify(jdbcMetadataSupport).tables(firstDataSource, "ds1", "reporting", "%", List.of("TABLE"));
    verify(jdbcMetadataSupport, never()).tables(eq(secondDataSource), any(), any(), any(), any());
  }

  @Test
  void catalogFilteringShouldTreatUnderscoreAsLiteral() {
    JdbcDataSourceDefinition underscoreDataSource = enabledDataSource("ds-1", "my_db");
    JdbcDataSourceDefinition otherDataSource = enabledDataSource("ds-2", "myXdb");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    final FederationJdbcDriverModels.TabularResult tableResult =
        new FederationJdbcDriverModels.TabularResult(
            List.of(
                new FederationJdbcDriverModels.JdbcColumn(
                    "TABLE_CAT", "VARCHAR", java.sql.Types.VARCHAR),
                new FederationJdbcDriverModels.JdbcColumn(
                    "TABLE_NAME", "VARCHAR", java.sql.Types.VARCHAR)),
            List.of(List.of("my_db", "users")));
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1"))
        .thenReturn(List.of(enabledGrant("grant-1", "ds-1"), enabledGrant("grant-2", "ds-2")));
    when(dataSourceService.listEnabledDefinitions())
        .thenReturn(List.of(underscoreDataSource, otherDataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.tables(underscoreDataSource, "my_db", null, "%", List.of("TABLE")))
        .thenReturn(tableResult);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", null, "tenant-a", "ctx-1"))) {
      FederationJdbcDriverModels.TabularResult response =
          service.tables(session, "ctx-1", "my_db", null, "%", List.of("TABLE"));

      assertThat(response.rows()).containsExactly(List.of("my_db", "users"));
    }

    verify(jdbcMetadataSupport).tables(underscoreDataSource, "my_db", null, "%", List.of("TABLE"));
    verify(jdbcMetadataSupport, never()).tables(eq(otherDataSource), any(), any(), any(), any());
  }

  @Test
  void metadataShouldReuseCachedResultAcrossSocketSessionRequests() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    FederationJdbcConnectionUser grant = enabledGrant("grant-1", "ds-1");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    final FederationJdbcDriverModels.TabularResult tableTypes =
        new FederationJdbcDriverModels.TabularResult(
            List.of(
                new FederationJdbcDriverModels.JdbcColumn(
                    "TABLE_TYPE", "VARCHAR", java.sql.Types.VARCHAR)),
            List.of(List.of("TABLE")));
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of(grant));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(dataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.tableTypes(dataSource)).thenReturn(tableTypes);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", "ds1", "tenant-a", "ctx-1"))) {
      FederationJdbcDriverModels.TabularResult first = service.tableTypes(session, "ctx-1");
      FederationJdbcDriverModels.TabularResult second = service.tableTypes(session, "ctx-1");

      assertThat(first.rows()).containsExactly(List.of("TABLE"));
      assertThat(second.rows()).containsExactly(List.of("TABLE"));
    }

    verify(jdbcMetadataSupport, times(1)).tableTypes(dataSource);
    verify(authorizationContextService, times(2))
        .calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any());
  }

  @Test
  void schemasShouldDelegateToMetadataSupport() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    final FederationJdbcDriverModels.TabularResult schemasResult =
        new FederationJdbcDriverModels.TabularResult(
            List.of(
                new FederationJdbcDriverModels.JdbcColumn(
                    "TABLE_SCHEM", "VARCHAR", java.sql.Types.VARCHAR),
                new FederationJdbcDriverModels.JdbcColumn(
                    "TABLE_CATALOG", "VARCHAR", java.sql.Types.VARCHAR)),
            List.of(List.of("PUBLIC", "ds1")));
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1"))
        .thenReturn(List.of(enabledGrant("g1", "ds-1")));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(dataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.schemas(dataSource, "ds1", "%")).thenReturn(schemasResult);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", "ds1", "tenant-a", "ctx-1"))) {
      FederationJdbcDriverModels.TabularResult response =
          service.schemas(session, "ctx-1", "ds1", "%");
      assertThat(response.rows()).containsExactly(List.of("PUBLIC", "ds1"));
    }

    verify(jdbcMetadataSupport).schemas(dataSource, "ds1", "%");
  }

  @Test
  void columnsShouldDelegateToMetadataSupport() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    final FederationJdbcDriverModels.TabularResult columnsResult =
        new FederationJdbcDriverModels.TabularResult(
            List.of(
                new FederationJdbcDriverModels.JdbcColumn(
                    "COLUMN_NAME", "VARCHAR", java.sql.Types.VARCHAR)),
            List.of(List.of("id")));
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1"))
        .thenReturn(List.of(enabledGrant("g1", "ds-1")));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(dataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.columns(dataSource, "ds1", "PUBLIC", "ORDERS", "%"))
        .thenReturn(columnsResult);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", "ds1", "tenant-a", "ctx-1"))) {
      FederationJdbcDriverModels.TabularResult response =
          service.columns(session, "ctx-1", "ds1", "PUBLIC", "ORDERS", "%");
      assertThat(response.rows()).containsExactly(List.of("id"));
    }
  }

  @Test
  void primaryKeysShouldDelegateToMetadataSupport() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    final FederationJdbcDriverModels.TabularResult pksResult =
        new FederationJdbcDriverModels.TabularResult(
            List.of(
                new FederationJdbcDriverModels.JdbcColumn(
                    "COLUMN_NAME", "VARCHAR", java.sql.Types.VARCHAR)),
            List.of(List.of("id")));
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1"))
        .thenReturn(List.of(enabledGrant("g1", "ds-1")));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(dataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.primaryKeys(dataSource, "ds1", "PUBLIC", "ORDERS"))
        .thenReturn(pksResult);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", "ds1", "tenant-a", "ctx-1"))) {
      FederationJdbcDriverModels.TabularResult response =
          service.primaryKeys(session, "ctx-1", "ds1", "PUBLIC", "ORDERS");
      assertThat(response.rows()).containsExactly(List.of("id"));
    }
  }

  @Test
  void indexInfoShouldDelegateToMetadataSupport() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    final FederationJdbcDriverModels.TabularResult indexResult =
        new FederationJdbcDriverModels.TabularResult(
            List.of(
                new FederationJdbcDriverModels.JdbcColumn(
                    "INDEX_NAME", "VARCHAR", java.sql.Types.VARCHAR)),
            List.of());
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1"))
        .thenReturn(List.of(enabledGrant("g1", "ds-1")));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(dataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.indexInfo(dataSource, "ds1", "PUBLIC", "ORDERS", false, true))
        .thenReturn(indexResult);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", "ds1", "tenant-a", "ctx-1"))) {
      FederationJdbcDriverModels.TabularResult response =
          service.indexInfo(session, "ctx-1", "ds1", "PUBLIC", "ORDERS", false, true);
      assertThat(response).isNotNull();
    }
  }

  @Test
  void importedKeysShouldDelegateToMetadataSupport() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    final FederationJdbcDriverModels.TabularResult keysResult =
        new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1"))
        .thenReturn(List.of(enabledGrant("g1", "ds-1")));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(dataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.importedKeys(dataSource, "ds1", "PUBLIC", "ORDERS"))
        .thenReturn(keysResult);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", "ds1", "tenant-a", "ctx-1"))) {
      FederationJdbcDriverModels.TabularResult response =
          service.importedKeys(session, "ctx-1", "ds1", "PUBLIC", "ORDERS");
      assertThat(response).isNotNull();
    }
  }

  @Test
  void exportedKeysShouldDelegateToMetadataSupport() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    final FederationJdbcDriverModels.TabularResult keysResult =
        new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1"))
        .thenReturn(List.of(enabledGrant("g1", "ds-1")));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(dataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.exportedKeys(dataSource, "ds1", "PUBLIC", "ORDERS"))
        .thenReturn(keysResult);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", "ds1", "tenant-a", "ctx-1"))) {
      FederationJdbcDriverModels.TabularResult response =
          service.exportedKeys(session, "ctx-1", "ds1", "PUBLIC", "ORDERS");
      assertThat(response).isNotNull();
    }
  }

  @Test
  void openSessionShouldFailWhenPasswordDoesNotMatch() {
    when(credentials.open(any())).thenThrow(new CredentialAuthenticationException(Code.INVALID));
    FederationJdbcDriverServiceImpl service = service();

    assertThrows(
        CredentialAuthenticationException.class,
        () ->
            service.openSession(
                new FederationJdbcDriverModels.DriverRequest(
                    "alice@example.com", "wrong-password", null, "tenant-a", null)));
  }

  @Test
  void openSessionShouldFailWhenUserIsDisabled() {
    when(credentials.open(any())).thenThrow(new CredentialAuthenticationException(Code.INVALID));
    FederationJdbcDriverServiceImpl service = service();

    assertThrows(
        CredentialAuthenticationException.class,
        () ->
            service.openSession(
                new FederationJdbcDriverModels.DriverRequest(
                    "alice@example.com", "secret", null, "tenant-a", null)));
  }

  @Test
  void openSessionShouldFailWhenUserHasNoAuthorizedGrants() {
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of());
    FederationJdbcDriverServiceImpl service = service();

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () ->
            service.openSession(
                new FederationJdbcDriverModels.DriverRequest(
                    "alice@example.com", "secret", null, "tenant-a", null)));
  }

  @Test
  void openSessionShouldResolveTenantFromServiceWhenNotSupplied() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    FederationJdbcConnectionUser grant = enabledGrant("grant-1", "ds-1");
    final AuthorizationContext authorizationContext = authorizationContext("user-1");
    final NamedTenantVo tenantVo =
        new NamedTenantVo("tenant-resolved", "Resolved Tenant", (TenantContextType) null);
    authenticate();
    when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of(grant));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(dataSource));
    when(tenantService.getTenantsByUserId("user-1")).thenReturn(Set.of(tenantVo));
    when(authorizationContextService.calculate(eq("tenant-resolved"), eq("user-1"), any(), any()))
        .thenReturn(authorizationContext);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", "secret", "ds1", null, "ctx-1"))) {
      FederationJdbcDriverModels.PingResult ping = service.ping(session, "ctx-1");
      assertThat(ping.tenantId()).isEqualTo("tenant-resolved");
    }
  }

  @Test
  void otpAndWhitespacePasswordReachTheIdentityOwnerUnchanged() {
    authenticate();
    grantConnection();
    var service = service();
    try (var session =
        service.openSession(
            new FederationJdbcDriverModels.DriverRequest(
                "alice@example.com", " secret ", "ds1", "tenant-a", null, "012345"))) {
      verify(credentials).open(new CredentialRequest("alice@example.com", " secret ", "012345"));
    }
    verify(credentials).close(HANDLE);
  }

  @Test
  void disabledExpiredAndChangedIdentityCannotUseAnExistingConnection() {
    authenticate();
    grantConnection();
    var service = service();
    try (var session = service.openSession(request())) {
      when(credentials.check(HANDLE))
          .thenThrow(new CredentialAuthenticationException(Code.SESSION_INVALID));
      assertThrows(CredentialAuthenticationException.class, () -> service.catalogs(session, null));
      org.mockito.Mockito.doReturn(
              new CredentialIdentity("user-1", 1, false, java.time.Instant.EPOCH))
          .when(credentials)
          .check(HANDLE);
      assertThrows(CredentialAuthenticationException.class, () -> service.catalogs(session, null));
      when(credentials.check(HANDLE))
          .thenReturn(new CredentialIdentity("user-1", 2, false, identity().expiresAt()));
      assertThrows(CredentialAuthenticationException.class, () -> service.catalogs(session, null));
      when(credentials.check(HANDLE))
          .thenReturn(new CredentialIdentity("other-user", 1, false, identity().expiresAt()));
      assertThrows(CredentialAuthenticationException.class, () -> service.catalogs(session, null));
    }
    verifyNoInteractions(sqlConsoleService, jdbcMetadataSupport, authorizationContextService);
  }

  @Test
  void revokedGrantsAndTenantMembershipTakeEffectOnAnOpenConnection() {
    authenticate();
    grantConnection();
    var service = service();
    try (var session = service.openSession(request())) {
      when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of());
      assertThrows(
          org.springframework.security.access.AccessDeniedException.class,
          () -> service.catalogs(session, null));
      when(jdbcConnectionUserService.enabledGrants("user-1"))
          .thenReturn(List.of(enabledGrant("g1", "ds-1")));
      when(tenantService.getTenantsByUserId("user-1")).thenReturn(Set.of());
      assertThrows(
          org.springframework.security.access.AccessDeniedException.class,
          () -> service.ping(session, null));
    }
    verifyNoInteractions(sqlConsoleService, jdbcMetadataSupport, authorizationContextService);
  }

  @Test
  void callerCannotSelectAnUnrelatedTenantAndUnusedProofIsRevoked() {
    authenticate();
    grantConnection();
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () ->
            service()
                .openSession(
                    new FederationJdbcDriverModels.DriverRequest(
                        "alice@example.com", "secret", "ds1", "unrelated", null)));
    verify(credentials).close(HANDLE);
  }

  @Test
  void closedAndForeignSessionsAreRejectedBeforeRemoteWork() {
    authenticate();
    grantConnection();
    var service = service();
    var session = service.openSession(request());
    assertThrows(CredentialAuthenticationException.class, () -> service().ping(session, null));
    session.close();
    session.close();
    assertThrows(CredentialAuthenticationException.class, () -> service.ping(session, null));
    verify(credentials, never()).check(anyString());
    verify(credentials).close(HANDLE);
  }

  @Test
  void outerSecurityAndAuthorizationContextsSurviveOperationFailure() {
    authenticate();
    grantConnection();
    var previousSecurity =
        org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
    var previousAuthorization = authorizationContext("outer-user");
    org.springframework.security.core.context.SecurityContextHolder.setContext(previousSecurity);
    org.simplepoint.core.RequestContextHolder.setContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, previousAuthorization);
    when(authorizationContextService.calculate(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("unavailable"));
    var service = service();
    try (var session = service.openSession(request())) {
      assertThrows(IllegalStateException.class, () -> service.ping(session, null));
      assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext())
          .isSameAs(previousSecurity);
      assertThat(
              org.simplepoint.core.RequestContextHolder.getContext(
                  org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
                  AuthorizationContext.class))
          .isSameAs(previousAuthorization);
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
      org.simplepoint.core.RequestContextHolder.clearContext(
          org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  @Test
  void metadataCacheHitCannotBypassRevokedMetadataPermission() {
    authenticate();
    grantConnection();
    when(authorizationContextService.calculate(any(), any(), any(), any()))
        .thenReturn(authorizationContext("user-1"));
    var cache =
        org.mockito.Mockito.mock(
            org.simplepoint.plugin.dna.federation.service.support.FederationMetadataCacheService
                .class);
    when(cache.scopedKey(any(), eq("catalogs"))).thenReturn("cached-catalogs");
    when(cache.get("cached-catalogs"))
        .thenReturn(
            new FederationJdbcDriverModels.TabularResult(List.of(), List.of(List.of("ds1"))));
    var service =
        new FederationJdbcDriverServiceImpl(
            dataSourceService,
            jdbcConnectionUserService,
            sqlConsoleService,
            new FederationJdbcMetadataQueryServiceImpl(jdbcMetadataSupport),
            credentials,
            tenantService,
            authorizationContextService,
            cache);
    try (var session = service.openSession(request())) {
      assertThat(service.catalogs(session, null).rows()).containsExactly(List.of("ds1"));
      var queryOnly = enabledGrant("g1", "ds-1");
      queryOnly.setOperationPermissions(Set.of("QUERY"));
      when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of(queryOnly));
      assertThrows(
          org.springframework.security.access.AccessDeniedException.class,
          () -> service.catalogs(session, null));
      verify(cache, times(1)).get("cached-catalogs");
    }
  }

  private void grantConnection() {
    when(jdbcConnectionUserService.enabledGrants("user-1"))
        .thenReturn(List.of(enabledGrant("g1", "ds-1")));
    when(dataSourceService.listEnabledDefinitions())
        .thenReturn(List.of(enabledDataSource("ds-1", "ds1")));
  }

  private static FederationJdbcDriverModels.DriverRequest request() {
    return new FederationJdbcDriverModels.DriverRequest(
        "alice@example.com", "secret", "ds1", "tenant-a", null);
  }

  private FederationJdbcDriverServiceImpl service() {
    return new FederationJdbcDriverServiceImpl(
        dataSourceService,
        jdbcConnectionUserService,
        sqlConsoleService,
        new FederationJdbcMetadataQueryServiceImpl(jdbcMetadataSupport),
        credentials,
        tenantService,
        authorizationContextService,
        new org.simplepoint.plugin.dna.federation.service.support.FederationMetadataCacheService());
  }

  private static AuthorizationContext authorizationContext(final String userId) {
    final AuthorizationContext authorizationContext = new AuthorizationContext();
    authorizationContext.setUserId(userId);
    return authorizationContext;
  }

  private static final String HANDLE = "a".repeat(43);

  private static CredentialIdentity identity() {
    return new CredentialIdentity(
        "user-1", 1, false, java.time.Instant.parse("2099-01-01T00:00:00Z"));
  }

  private void authenticate() {
    when(credentials.open(any())).thenReturn(new CredentialSession(HANDLE, identity()));
    org.mockito.Mockito.lenient().when(credentials.check(HANDLE)).thenReturn(identity());
    org.mockito.Mockito.lenient()
        .when(tenantService.getTenantsByUserId("user-1"))
        .thenReturn(Set.of(new NamedTenantVo("tenant-a", "Tenant A", (TenantContextType) null)));
  }

  private static JdbcDataSourceDefinition enabledDataSource(final String id, final String code) {
    JdbcDataSourceDefinition dataSource = new JdbcDataSourceDefinition();
    dataSource.setId(id);
    dataSource.setCode(code);
    dataSource.setName("Data Source " + code);
    dataSource.setEnabled(true);
    return dataSource;
  }

  private static FederationJdbcConnectionUser enabledGrant(
      final String id, final String dataSourceId) {
    FederationJdbcConnectionUser grant = new FederationJdbcConnectionUser();
    grant.setId(id);
    grant.setCatalogId(dataSourceId);
    grant.setEnabled(true);
    grant.setOperationPermissions(Set.of("METADATA", "QUERY"));
    return grant;
  }
}
