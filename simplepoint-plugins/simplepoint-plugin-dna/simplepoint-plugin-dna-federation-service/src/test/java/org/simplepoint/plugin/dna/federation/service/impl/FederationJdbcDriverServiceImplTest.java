package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.security.context.AuthorizationContextService;
import org.simplepoint.security.entity.User;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class FederationJdbcDriverServiceImplTest {

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @Mock
  private FederationJdbcConnectionUserService jdbcConnectionUserService;

  @Mock
  private FederationSqlConsoleService sqlConsoleService;

  @Mock
  private FederationJdbcMetadataSupport jdbcMetadataSupport;

  @Mock
  private UsersService usersService;

  @Mock
  private PasswordEncoder passwordEncoder;

  @Mock
  private TenantService tenantService;

  @Mock
  private AuthorizationContextService authorizationContextService;

  @Test
  void reusedSocketSessionShouldResolveAuthenticationAndAuthorizationOnce() {
    User user = enabledUser();
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    FederationJdbcConnectionUser grant = enabledGrant("grant-1", "ds-1");
    AuthorizationContext authorizationContext = authorizationContext("user-1");
    when(usersService.loadUserByPhoneOrEmail("alice@example.com")).thenReturn(user);
    when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
    when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of(grant));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(dataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session = service.openSession(
        new FederationJdbcDriverModels.DriverRequest("alice@example.com", "secret", "ds1", "tenant-a", "ctx-1")
    )) {
      FederationJdbcDriverModels.PingResult first = service.ping(session, "ctx-1");
      FederationJdbcDriverModels.PingResult second = service.ping(session, "ctx-1");

      assertThat(first.catalogCode()).isEqualTo("ds1");
      assertThat(second.userId()).isEqualTo("user-1");
    }

    verify(usersService, times(1)).loadUserByPhoneOrEmail("alice@example.com");
    verify(passwordEncoder, times(1)).matches("secret", "encoded");
    verify(jdbcConnectionUserService, times(1)).enabledGrants("user-1");
    verify(dataSourceService, times(1)).listEnabledDefinitions();
    verify(dataSourceService, never()).findActiveByCode(anyString());
    verify(authorizationContextService, times(1)).calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any());
    verifyNoInteractions(sqlConsoleService, jdbcMetadataSupport, tenantService);
  }

  @Test
  void openSessionWithoutCatalogShouldExposeAllAuthorizedCatalogs() {
    User user = enabledUser();
    JdbcDataSourceDefinition firstDataSource = enabledDataSource("ds-1", "ds1");
    JdbcDataSourceDefinition secondDataSource = enabledDataSource("ds-2", "ds2");
    AuthorizationContext authorizationContext = authorizationContext("user-1");
    when(usersService.loadUserByPhoneOrEmail("alice@example.com")).thenReturn(user);
    when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
    when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of(
        enabledGrant("grant-1", "ds-1"),
        enabledGrant("grant-2", "ds-2")
    ));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(firstDataSource, secondDataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session = service.openSession(
        new FederationJdbcDriverModels.DriverRequest("alice@example.com", "secret", null, "tenant-a", "ctx-1")
    )) {
      FederationJdbcDriverModels.PingResult ping = service.ping(session, "ctx-1");
      FederationJdbcDriverModels.TabularResult catalogs = service.catalogs(session, "ctx-1");

      assertThat(ping.catalogCode()).isNull();
      assertThat(catalogs.rows()).containsExactly(List.of("ds1"), List.of("ds2"));
    }

    verifyNoInteractions(jdbcMetadataSupport, sqlConsoleService, tenantService);
  }

  @Test
  void queryShouldUseRequestedCatalogFromQueryPayloadWhenSessionHasNoDefaultCatalog() {
    User user = enabledUser();
    JdbcDataSourceDefinition firstDataSource = enabledDataSource("ds-1", "ds1");
    JdbcDataSourceDefinition secondDataSource = enabledDataSource("ds-2", "ds2");
    AuthorizationContext authorizationContext = authorizationContext("user-1");
    FederationQueryModels.SqlQueryResult queryResult = new FederationQueryModels.SqlQueryResult(
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
        "命中数据源: ds1"
    );
    when(usersService.loadUserByPhoneOrEmail("alice@example.com")).thenReturn(user);
    when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
    when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of(
        enabledGrant("grant-1", "ds-1"),
        enabledGrant("grant-2", "ds-2")
    ));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(firstDataSource, secondDataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(sqlConsoleService.execute(eq("ds-1"), any(FederationQueryModels.SqlConsoleRequest.class))).thenReturn(queryResult);
    FederationJdbcDriverServiceImpl service = service();

    FederationQueryModels.SqlQueryResult response = service.query(
        new FederationJdbcDriverModels.DriverRequest("alice@example.com", "secret", null, "tenant-a", "ctx-1"),
        new FederationJdbcDriverModels.QueryRequest("select id from orders", "public", "ds1")
    );

    assertThat(response.catalogCode()).isEqualTo("ds1");
    verify(sqlConsoleService).execute(eq("ds-1"), argThat((FederationQueryModels.SqlConsoleRequest request) ->
        "ds1".equals(request.catalogCode())
            && "select id from orders".equals(request.sql())
            && "public".equals(request.defaultSchema())
    ));
  }

  @Test
  void tablesShouldOnlyLoadRequestedCatalogMetadata() {
    User user = enabledUser();
    JdbcDataSourceDefinition firstDataSource = enabledDataSource("ds-1", "ds1");
    JdbcDataSourceDefinition secondDataSource = enabledDataSource("ds-2", "ds2");
    AuthorizationContext authorizationContext = authorizationContext("user-1");
    FederationJdbcDriverModels.TabularResult tableResult = new FederationJdbcDriverModels.TabularResult(
        List.of(
            new FederationJdbcDriverModels.JdbcColumn("TABLE_CAT", "VARCHAR", java.sql.Types.VARCHAR),
            new FederationJdbcDriverModels.JdbcColumn("TABLE_SCHEM", "VARCHAR", java.sql.Types.VARCHAR),
            new FederationJdbcDriverModels.JdbcColumn("TABLE_NAME", "VARCHAR", java.sql.Types.VARCHAR)
        ),
        List.of(List.of("ds1", "reporting", "orders"))
    );
    when(usersService.loadUserByPhoneOrEmail("alice@example.com")).thenReturn(user);
    when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
    when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of(
        enabledGrant("grant-1", "ds-1"),
        enabledGrant("grant-2", "ds-2")
    ));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(firstDataSource, secondDataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.tables(firstDataSource, "ds1", "reporting", "%", List.of("TABLE"))).thenReturn(tableResult);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session = service.openSession(
        new FederationJdbcDriverModels.DriverRequest("alice@example.com", "secret", null, "tenant-a", "ctx-1")
    )) {
      FederationJdbcDriverModels.TabularResult response = service.tables(
          session,
          "ctx-1",
          "ds1",
          "reporting",
          "%",
          List.of("TABLE")
      );

      assertThat(response.rows()).containsExactly(List.of("ds1", "reporting", "orders"));
    }

    verify(jdbcMetadataSupport).tables(firstDataSource, "ds1", "reporting", "%", List.of("TABLE"));
    verify(jdbcMetadataSupport, never()).tables(eq(secondDataSource), any(), any(), any(), any());
  }

  @Test
  void catalogFilteringShouldTreatUnderscoreAsLiteral() {
    User user = enabledUser();
    JdbcDataSourceDefinition underscoreDataSource = enabledDataSource("ds-1", "my_db");
    JdbcDataSourceDefinition otherDataSource = enabledDataSource("ds-2", "myXdb");
    AuthorizationContext authorizationContext = authorizationContext("user-1");
    FederationJdbcDriverModels.TabularResult tableResult = new FederationJdbcDriverModels.TabularResult(
        List.of(
            new FederationJdbcDriverModels.JdbcColumn("TABLE_CAT", "VARCHAR", java.sql.Types.VARCHAR),
            new FederationJdbcDriverModels.JdbcColumn("TABLE_NAME", "VARCHAR", java.sql.Types.VARCHAR)
        ),
        List.of(List.of("my_db", "users"))
    );
    when(usersService.loadUserByPhoneOrEmail("alice@example.com")).thenReturn(user);
    when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
    when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of(
        enabledGrant("grant-1", "ds-1"),
        enabledGrant("grant-2", "ds-2")
    ));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(underscoreDataSource, otherDataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.tables(underscoreDataSource, "my_db", null, "%", List.of("TABLE")))
        .thenReturn(tableResult);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session = service.openSession(
        new FederationJdbcDriverModels.DriverRequest("alice@example.com", "secret", null, "tenant-a", "ctx-1")
    )) {
      FederationJdbcDriverModels.TabularResult response = service.tables(
          session, "ctx-1", "my_db", null, "%", List.of("TABLE")
      );

      assertThat(response.rows()).containsExactly(List.of("my_db", "users"));
    }

    verify(jdbcMetadataSupport).tables(underscoreDataSource, "my_db", null, "%", List.of("TABLE"));
    verify(jdbcMetadataSupport, never()).tables(eq(otherDataSource), any(), any(), any(), any());
  }

  @Test
  void metadataShouldReuseCachedResultAcrossSocketSessionRequests() {
    User user = enabledUser();
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    FederationJdbcConnectionUser grant = enabledGrant("grant-1", "ds-1");
    AuthorizationContext authorizationContext = authorizationContext("user-1");
    FederationJdbcDriverModels.TabularResult tableTypes = new FederationJdbcDriverModels.TabularResult(
        List.of(new FederationJdbcDriverModels.JdbcColumn("TABLE_TYPE", "VARCHAR", java.sql.Types.VARCHAR)),
        List.of(List.of("TABLE"))
    );
    when(usersService.loadUserByPhoneOrEmail("alice@example.com")).thenReturn(user);
    when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
    when(jdbcConnectionUserService.enabledGrants("user-1")).thenReturn(List.of(grant));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(dataSource));
    when(authorizationContextService.calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any()))
        .thenReturn(authorizationContext);
    when(jdbcMetadataSupport.tableTypes(dataSource)).thenReturn(tableTypes);
    FederationJdbcDriverServiceImpl service = service();

    try (FederationJdbcDriverService.DriverSession session = service.openSession(
        new FederationJdbcDriverModels.DriverRequest("alice@example.com", "secret", "ds1", "tenant-a", "ctx-1")
    )) {
      FederationJdbcDriverModels.TabularResult first = service.tableTypes(session, "ctx-1");
      FederationJdbcDriverModels.TabularResult second = service.tableTypes(session, "ctx-1");

      assertThat(first.rows()).containsExactly(List.of("TABLE"));
      assertThat(second.rows()).containsExactly(List.of("TABLE"));
    }

    verify(jdbcMetadataSupport, times(1)).tableTypes(dataSource);
    verify(authorizationContextService, times(1)).calculate(eq("tenant-a"), eq("user-1"), eq("ctx-1"), any());
  }

  private FederationJdbcDriverServiceImpl service() {
    return new FederationJdbcDriverServiceImpl(
        dataSourceService,
        jdbcConnectionUserService,
        sqlConsoleService,
        jdbcMetadataSupport,
        usersService,
        passwordEncoder,
        tenantService,
        authorizationContextService,
        new org.simplepoint.plugin.dna.federation.service.support.FederationMetadataCacheService()
    );
  }

  private static AuthorizationContext authorizationContext(final String userId) {
    AuthorizationContext authorizationContext = new AuthorizationContext();
    authorizationContext.setUserId(userId);
    return authorizationContext;
  }

  private static User enabledUser() {
    User user = new User();
    user.setId("user-1");
    user.setEnabled(true);
    user.setPassword("encoded");
    user.setEmail("alice@example.com");
    user.setSuperAdmin(false);
    return user;
  }

  private static JdbcDataSourceDefinition enabledDataSource(final String id, final String code) {
    JdbcDataSourceDefinition dataSource = new JdbcDataSourceDefinition();
    dataSource.setId(id);
    dataSource.setCode(code);
    dataSource.setName("Data Source " + code);
    dataSource.setEnabled(true);
    return dataSource;
  }

  private static FederationJdbcConnectionUser enabledGrant(final String id, final String dataSourceId) {
    FederationJdbcConnectionUser grant = new FederationJdbcConnectionUser();
    grant.setId(id);
    grant.setCatalogId(dataSourceId);
    grant.setEnabled(true);
    grant.setOperationPermissions(Set.of("METADATA", "QUERY"));
    return grant;
  }
}
