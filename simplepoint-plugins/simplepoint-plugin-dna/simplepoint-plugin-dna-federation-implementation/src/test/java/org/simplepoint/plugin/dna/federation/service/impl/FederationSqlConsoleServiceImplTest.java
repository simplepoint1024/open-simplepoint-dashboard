package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.calcite.adapter.jdbc.SafeJdbcSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.data.calcite.core.query.CalciteQueryAnalysis;
import org.simplepoint.data.calcite.core.query.CalciteQueryColumn;
import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.CalciteUpdateResult;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryPolicyRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.simplepoint.plugin.dna.federation.service.support.FederationCalciteCatalogAssembler;

@ExtendWith(MockitoExtension.class)
class FederationSqlConsoleServiceImplTest {

  @Mock private JdbcDataSourceDefinitionService dataSourceService;

  @Mock private FederationQueryPolicyRepository policyRepository;

  @Mock private FederationQueryAuditService auditService;

  @Mock private FederationCalciteCatalogAssembler catalogAssembler;

  @Mock private CalciteQueryEngine queryEngine;

  @Mock private FederationSqlWriteOwnership writeOwnership;

  @Test
  void executeShouldNotMountAnyDatasourceForLocalQuery() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    FederationQueryPolicy policy = enabledPolicy("ds-1", true);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "ds1", List.of(), rootSchema -> {});
    CalciteQueryAnalysis analysis =
        new CalciteQueryAnalysis("EnumerableValues(tuples=[[{ 1 }]])", List.of(), false);
    CalciteQueryResult queryResult =
        new CalciteQueryResult(
            List.of(new CalciteQueryColumn("EXPR$0", "INTEGER")),
            List.of(List.of(1)),
            false,
            1,
            3L,
            analysis);
    when(policyRepository.findAllActiveByCatalogId("ds-1")).thenReturn(List.of(policy));
    when(catalogAssembler.assemble(
            eq("ds1"),
            argThat((List<JdbcDataSourceDefinition> definitions) -> definitions.isEmpty()),
            anyMap()))
        .thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    when(queryEngine.execute(any(), any(), any())).thenReturn(queryResult);
    when(auditService.create(any(FederationQueryAudit.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    FederationSqlConsoleServiceImpl service = service();

    when(dataSourceService.findActiveById("ds-1")).thenReturn(java.util.Optional.of(dataSource));

    final FederationQueryModels.SqlQueryResult response =
        service.execute("ds-1", new FederationQueryModels.SqlConsoleRequest("ds1", "select 1"));

    assertEquals("ds1", response.catalogCode());
    assertTrue(response.dataSources().isEmpty());
    verify(dataSourceService, never()).listEnabledDefinitions();
    verify(catalogAssembler)
        .assemble(
            eq("ds1"),
            argThat((List<JdbcDataSourceDefinition> definitions) -> definitions.isEmpty()),
            anyMap());
  }

  @Test
  void executeShouldForwardDefaultSchemaParametersAndPerRunMaxRowsToCalcite() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    FederationQueryPolicy policy = enabledPolicy("ds-1", true);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "ds1", List.of(), rootSchema -> {});
    CalciteQueryAnalysis analysis =
        new CalciteQueryAnalysis("EnumerableValues(tuples=[[{ 7 }]])", List.of(), false);
    CalciteQueryResult queryResult =
        new CalciteQueryResult(
            List.of(new CalciteQueryColumn("EXPR$0", "INTEGER")),
            List.of(List.of(7)),
            false,
            1,
            2L,
            analysis);
    when(policyRepository.findAllActiveByCatalogId("ds-1")).thenReturn(List.of(policy));
    when(catalogAssembler.assemble(
            eq("ds1"),
            argThat((List<JdbcDataSourceDefinition> definitions) -> definitions.isEmpty()),
            anyMap()))
        .thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    when(queryEngine.execute(any(), any(), any())).thenReturn(queryResult);
    when(auditService.create(any(FederationQueryAudit.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    FederationSqlConsoleServiceImpl service = service();

    when(dataSourceService.findActiveById("ds-1")).thenReturn(java.util.Optional.of(dataSource));

    FederationQueryModels.SqlQueryResult response =
        service.execute(
            "ds-1",
            new FederationQueryModels.SqlConsoleRequest(
                "ds1", "select ?", "public", List.of(7), 25));
    assertEquals(25, response.maxRows());

    ArgumentCaptor<CalciteQueryRequest> explainRequest =
        ArgumentCaptor.forClass(CalciteQueryRequest.class);
    ArgumentCaptor<CalciteQueryRequest> executeRequest =
        ArgumentCaptor.forClass(CalciteQueryRequest.class);
    verify(queryEngine).explain(explainRequest.capture(), any());
    verify(queryEngine).execute(executeRequest.capture(), any(), any());
    assertEquals("public", explainRequest.getValue().defaultSchema());
    assertEquals("public", executeRequest.getValue().defaultSchema());
    assertEquals(List.of(7), explainRequest.getValue().parameters());
    assertEquals(List.of(7), executeRequest.getValue().parameters());
    assertEquals(25, explainRequest.getValue().maxRows());
    assertEquals(25, executeRequest.getValue().maxRows());
  }

  @Test
  void explainShouldReturnEffectivePerRunMaxRows() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    FederationQueryPolicy policy = enabledPolicy("ds-1", true);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "ds1", List.of(), rootSchema -> {});
    CalciteQueryAnalysis analysis =
        new CalciteQueryAnalysis("EnumerableValues(tuples=[[{ 1 }]])", List.of(), false);
    when(policyRepository.findAllActiveByCatalogId("ds-1")).thenReturn(List.of(policy));
    when(catalogAssembler.assemble(
            eq("ds1"),
            argThat((List<JdbcDataSourceDefinition> definitions) -> definitions.isEmpty()),
            anyMap()))
        .thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    FederationSqlConsoleServiceImpl service = service();

    when(dataSourceService.findActiveByCode("ds1")).thenReturn(java.util.Optional.of(dataSource));

    FederationQueryModels.SqlExplainResult response =
        service.explain(
            new FederationQueryModels.SqlConsoleRequest("ds1", "select 1", null, null, 25));

    ArgumentCaptor<CalciteQueryRequest> explainRequest =
        ArgumentCaptor.forClass(CalciteQueryRequest.class);
    verify(queryEngine).explain(explainRequest.capture(), any());
    assertEquals(25, explainRequest.getValue().maxRows());
    assertEquals(25, response.maxRows());
  }

  @Test
  void executeShouldRejectCrossSourceJoinWhenPolicyDisallowsIt() {
    JdbcDataSourceDefinition selected = enabledDataSource("ds-1", "ds1");
    JdbcDataSourceDefinition other = enabledDataSource("ds-2", "ds2");
    FederationQueryPolicy policy = enabledPolicy("ds-1", false);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "ds1", List.of("ds1", "ds2"), rootSchema -> {});
    CalciteQueryAnalysis analysis =
        new CalciteQueryAnalysis(
            """
            EnumerableHashJoin(condition=[=($0, $2)], joinType=[inner])
              JdbcTableScan(table=[[ds1, PUBLIC, ORDERS]])
              JdbcTableScan(table=[[ds2, PUBLIC, CUSTOMERS]])
            """,
            List.of("Filter"),
            true);
    when(policyRepository.findAllActiveByCatalogId("ds-1")).thenReturn(List.of(policy));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected, other));
    when(catalogAssembler.assemble(
            eq("ds1"),
            argThat(
                definitions ->
                    definitions.size() == 2
                        && definitions.stream()
                            .map(JdbcDataSourceDefinition::getCode)
                            .toList()
                            .containsAll(List.of("ds1", "ds2"))),
            anyMap()))
        .thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    when(auditService.create(any(FederationQueryAudit.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    FederationSqlConsoleServiceImpl service = service();

    when(dataSourceService.findActiveById("ds-1")).thenReturn(java.util.Optional.of(selected));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.execute(
                    "ds-1",
                    new FederationQueryModels.SqlConsoleRequest(
                        "ds1",
                        "select * from ds1.orders join ds2.customers on ds1.orders.customer_id ="
                            + " ds2.customers.id")));

    assertTrue(exception.getMessage().contains("禁止跨数据源 Join"));
    verify(queryEngine, never()).execute(any(), any());
    verify(auditService)
        .create(
            argThat(
                (FederationQueryAudit audit) ->
                    "REJECTED".equals(audit.getStatus())
                        && "ds1".equals(audit.getCatalogCode())
                        && audit.getErrorMessage() != null));
  }

  @Test
  void executeShouldReturnResultAndWriteSuccessAudit() {
    JdbcDataSourceDefinition selected = enabledDataSource("ds-1", "ds1");
    JdbcDataSourceDefinition other = enabledDataSource("ds-2", "ds2");
    FederationQueryPolicy policy = enabledPolicy("ds-1", true);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "ds1", List.of("ds1", "ds2"), rootSchema -> {});
    CalciteQueryAnalysis analysis =
        new CalciteQueryAnalysis(
            """
            EnumerableHashJoin(condition=[=($0, $2)], joinType=[inner])
              JdbcFilter(condition=[>($2, 0)])
                JdbcTableScan(table=[[ds1, PUBLIC, ORDERS]])
              JdbcTableScan(table=[[ds2, PUBLIC, CUSTOMERS]])
            """,
            List.of("Filter"),
            true);
    CalciteQueryResult queryResult =
        new CalciteQueryResult(
            List.of(
                new CalciteQueryColumn("id", "INTEGER"), new CalciteQueryColumn("name", "VARCHAR")),
            List.of(List.of(1, "Alice")),
            false,
            1,
            42L,
            analysis);
    when(policyRepository.findAllActiveByCatalogId("ds-1")).thenReturn(List.of(policy));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected, other));
    when(catalogAssembler.assemble(
            eq("ds1"),
            argThat(
                definitions ->
                    definitions.size() == 2
                        && definitions.stream()
                            .map(JdbcDataSourceDefinition::getCode)
                            .toList()
                            .containsAll(List.of("ds1", "ds2"))),
            anyMap()))
        .thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    when(queryEngine.execute(any(), any(), any())).thenReturn(queryResult);
    when(auditService.create(any(FederationQueryAudit.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    FederationSqlConsoleServiceImpl service = service();

    when(dataSourceService.findActiveById("ds-1")).thenReturn(java.util.Optional.of(selected));

    FederationQueryModels.SqlQueryResult response =
        service.execute(
            "ds-1",
            new FederationQueryModels.SqlConsoleRequest(
                "ds1",
                "select o.id, c.name from ds1.orders o join ds2.customers c on o.customer_id ="
                    + " c.id"));

    assertEquals("ds1", response.catalogCode());
    assertEquals("policy-demo", response.policyCode());
    assertEquals(2, response.dataSources().size());
    assertTrue(response.crossSourceJoin());
    assertEquals(List.of("Filter"), response.pushedDownOperators());
    assertTrue(response.platformJoin());
    assertEquals(1, response.returnedRows());
    assertEquals("Alice", response.rows().get(0).get(1));
    assertTrue(response.pushdownSummary().contains("命中数据源"));
    verify(auditService)
        .create(
            argThat(
                (FederationQueryAudit audit) ->
                    "SUCCESS".equals(audit.getStatus())
                        && Long.valueOf(1L).equals(audit.getResultRows())
                        && audit.getErrorMessage() == null));
  }

  @Test
  void executeShouldNormalizeQualifiedIdentifiersForJdbcQueries() {
    JdbcDataSourceDefinition selected = enabledDataSource("pg-1", "pg");
    FederationQueryPolicy policy = enabledPolicy("pg-1", true);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "pg", List.of("pg"), rootSchema -> {});
    CalciteQueryAnalysis analysis =
        new CalciteQueryAnalysis(
            """
            JdbcTableScan(table=[[pg, reporting, ORDERS]])
            """,
            List.of(),
            false);
    CalciteQueryResult queryResult =
        new CalciteQueryResult(
            List.of(new CalciteQueryColumn("total", "INTEGER")),
            List.of(List.of(1)),
            false,
            1,
            8L,
            analysis);
    when(policyRepository.findAllActiveByCatalogId("pg-1")).thenReturn(List.of(policy));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected));
    when(catalogAssembler.assemble(
            eq("pg"),
            argThat(
                definitions ->
                    definitions.size() == 1
                        && definitions.stream()
                            .map(JdbcDataSourceDefinition::getCode)
                            .toList()
                            .contains("pg")),
            anyMap()))
        .thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    when(queryEngine.execute(any(), any(), any())).thenReturn(queryResult);
    when(auditService.create(any(FederationQueryAudit.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    FederationSqlConsoleServiceImpl service = service();

    when(dataSourceService.findActiveById("pg-1")).thenReturn(java.util.Optional.of(selected));

    FederationQueryModels.SqlQueryResult response =
        service.execute(
            "pg-1",
            new FederationQueryModels.SqlConsoleRequest(
                "pg", "select count(*) from pg.reporting.orders"));

    ArgumentCaptor<CalciteQueryRequest> explainRequest =
        ArgumentCaptor.forClass(CalciteQueryRequest.class);
    final ArgumentCaptor<CalciteQueryRequest> executeRequest =
        ArgumentCaptor.forClass(CalciteQueryRequest.class);
    assertEquals(1, response.returnedRows());
    assertEquals(1, response.rows().get(0).get(0));
    verify(queryEngine).explain(explainRequest.capture(), any());
    verify(queryEngine).execute(executeRequest.capture(), any(), any());
    assertTrue(usesQuotedQualifiedName(explainRequest.getValue()), explainRequest.getValue().sql());
    assertTrue(usesQuotedQualifiedName(executeRequest.getValue()), executeRequest.getValue().sql());
  }

  private FederationSqlConsoleServiceImpl service() {
    return new FederationSqlConsoleServiceImpl(
        dataSourceService,
        policyRepository,
        auditService,
        catalogAssembler,
        queryEngine,
        new org.simplepoint.plugin.dna.federation.service.support.FederationMetadataCacheService(),
        writeOwnership);
  }

  private static JdbcDataSourceDefinition enabledDataSource(final String id, final String code) {
    JdbcDataSourceDefinition dataSource = new JdbcDataSourceDefinition();
    dataSource.setId(id);
    dataSource.setCode(code);
    dataSource.setName(code.toUpperCase());
    dataSource.setEnabled(true);
    return dataSource;
  }

  private static FederationQueryPolicy enabledPolicy(
      final String dataSourceId, final boolean allowCrossSourceJoin) {
    FederationQueryPolicy policy = new FederationQueryPolicy();
    policy.setId("policy-1");
    policy.setCatalogId(dataSourceId);
    policy.setCode("policy-demo");
    policy.setName("Demo Policy");
    policy.setEnabled(true);
    policy.setAllowSqlConsole(true);
    policy.setAllowCrossSourceJoin(allowCrossSourceJoin);
    policy.setMaxRows(200);
    policy.setTimeoutMs(15_000);
    policy.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    policy.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));
    return policy;
  }

  // ----- parameter validation -----

  @Test
  void executeShouldThrowWhenRequestIsNull() {
    FederationSqlConsoleServiceImpl service = service();
    assertThrows(IllegalArgumentException.class, () -> service.execute((String) null, null));
  }

  @Test
  void executeShouldThrowWhenSqlIsBlank() {
    FederationSqlConsoleServiceImpl service = service();
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(new FederationQueryModels.SqlConsoleRequest("ds1", "  ")));
  }

  @Test
  void executeShouldThrowWhenCatalogCodeIsBlank() {
    FederationSqlConsoleServiceImpl service = service();
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(new FederationQueryModels.SqlConsoleRequest("", "SELECT 1")));
  }

  @Test
  void executeShouldThrowWhenSqlExceedsMaxLength() {
    FederationSqlConsoleServiceImpl service = service();
    String longSql = "SELECT " + "x,".repeat(7_000);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(new FederationQueryModels.SqlConsoleRequest("ds1", longSql)));
  }

  @Test
  void executeUpdateShouldThrowWhenRequestIsNull() {
    FederationSqlConsoleServiceImpl service = service();
    assertThrows(IllegalArgumentException.class, () -> service.executeUpdate(null));
  }

  @Test
  void executeUpdateShouldThrowWhenSqlIsBlank() {
    FederationSqlConsoleServiceImpl service = service();
    assertThrows(
        IllegalArgumentException.class,
        () -> service.executeUpdate(new FederationQueryModels.SqlConsoleRequest("ds1", "")));
  }

  @Test
  void standardDmlShouldExecuteThroughCalciteJdbcAdapter() {
    JdbcDataSourceDefinition source = enabledDataSource("ds-1", "ds1");
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "ds1", List.of("ds1"), root -> {});
    when(dataSourceService.findActiveByCode("ds1")).thenReturn(java.util.Optional.of(source));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(source));
    when(catalogAssembler.assembleForWrite(
            eq("ds1"),
            argThat(
                (java.util.Collection<JdbcDataSourceDefinition> sources) ->
                    sources.size() == 1 && sources.iterator().next() == source),
            anyMap()))
        .thenReturn(assembly);
    when(queryEngine.executeUpdate(any(CalciteUpdateRequest.class), any()))
        .thenReturn(new CalciteUpdateResult(1, 4L, List.of("UPDATE orders SET id=1")));
    var ownership = mock(FederationSqlWriteOwnership.CalciteScope.class);
    when(writeOwnership.openCalcite(eq(assembly), any(FederationDmlPlan.class), eq("ds1")))
        .thenReturn(ownership);
    when(auditService.create(any(FederationQueryAudit.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    FederationQueryModels.SqlExecuteResult response =
        service()
            .smartExecute(
                new FederationQueryModels.SqlConsoleRequest(
                    "ds1",
                    "-- generated statement\n/* classifier comment */\n"
                        + "UPDATE ds1.orders SET id=1"));

    assertEquals("DML", response.type());
    assertEquals(1, response.updateResult().affectedRows());
    verify(ownership).requireVerified();
    verify(ownership).close();
    verify(queryEngine)
        .executeUpdate(
            argThat(
                request ->
                    request.sql().contains("\"ds1\".\"orders\"")
                        && request.defaultSchema().equals("ds1")
                        && request.timeoutMs() == 60_000),
            eq(ownership));
    verify(catalogAssembler)
        .assembleForWrite(
            eq("ds1"), any(java.util.Collection.class), argThat(catalogs -> catalogs.isEmpty()));
    verify(dataSourceService, never()).requireSimpleDataSource(any());
  }

  @Test
  void standardDmlShouldMountOnlyTheReferencedCatalogNamespace() {
    JdbcDataSourceDefinition source = enabledDataSource("ds-1", "ds1");
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "ds1", List.of("ds1"), root -> {});
    when(dataSourceService.findActiveByCode("ds1")).thenReturn(java.util.Optional.of(source));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(source));
    when(catalogAssembler.assembleForWrite(eq("ds1"), any(java.util.Collection.class), anyMap()))
        .thenReturn(assembly);
    when(queryEngine.executeUpdate(any(CalciteUpdateRequest.class), any()))
        .thenReturn(new CalciteUpdateResult(1, 4L, List.of()));
    when(writeOwnership.openCalcite(eq(assembly), any(FederationDmlPlan.class), eq("ds1")))
        .thenReturn(mock(FederationSqlWriteOwnership.CalciteScope.class));
    when(auditService.create(any(FederationQueryAudit.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service()
        .smartExecute(
            new FederationQueryModels.SqlConsoleRequest(
                "ds1", "UPDATE ds1.public.orders SET id=1"));

    verify(catalogAssembler)
        .assembleForWrite(
            eq("ds1"),
            any(java.util.Collection.class),
            argThat(catalogs -> catalogs.equals(Map.of("ds1", Set.of("public")))));
  }

  @Test
  void sqlConsoleDmlRoutingReachesCalciteWithTestOwnershipAdapter() throws Exception {
    JdbcDataSource physical = new JdbcDataSource();
    physical.setURL(
        "jdbc:h2:mem:federation-calcite-dml-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    physical.setUser("sa");
    physical.setPassword("");
    try (Connection connection = physical.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "create table orders (id int primary key, amount int not null, name varchar(100))");
      statement.execute("insert into orders values(1,0,null)");
    }
    JdbcDataSourceDefinition source = enabledDataSource("ds-1", "ds1");
    var assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "ds1",
            List.of("ds1"),
            root -> root.add("ds1", SafeJdbcSchema.create(root, "ds1", physical, null, "PUBLIC")));
    when(dataSourceService.findActiveByCode("ds1")).thenReturn(java.util.Optional.of(source));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(source));
    when(catalogAssembler.assembleForWrite(eq("ds1"), any(java.util.Collection.class), anyMap()))
        .thenReturn(assembly);
    when(auditService.create(any(FederationQueryAudit.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    // This H2 test isolates routing and parameter behavior. Ownership uses the native database
    // suite with the real console, assembler, ownership directory and Calcite implementation.
    var ownership = mock(FederationSqlWriteOwnership.CalciteScope.class);
    doAnswer(
            invocation -> {
              assembly.schemaConfigurer().configure(invocation.getArgument(0));
              return null;
            })
        .when(ownership)
        .configure(any());
    when(ownership.writeAdmission()).thenReturn(plan -> {});
    when(writeOwnership.openCalcite(eq(assembly), any(FederationDmlPlan.class), eq("ds1")))
        .thenReturn(ownership);
    FederationSqlConsoleServiceImpl service =
        new FederationSqlConsoleServiceImpl(
            dataSourceService,
            policyRepository,
            auditService,
            catalogAssembler,
            new DefaultCalciteQueryEngine(),
            new org.simplepoint.plugin.dna.federation.service.support
                .FederationMetadataCacheService(),
            writeOwnership);

    FederationQueryModels.SqlExecuteResult response =
        service.smartExecute(
            new FederationQueryModels.SqlConsoleRequest(
                "ds1",
                "UPDATE ds1.orders SET amount=?,name=? WHERE id=?",
                null,
                List.of(10, "测试-日本語", 1)));

    assertEquals("DML", response.type());
    assertEquals(1, response.updateResult().affectedRows());
    assertEquals(
        1,
        service
            .smartExecute(
                new FederationQueryModels.SqlConsoleRequest(
                    "ds1", "UPDATE ds1.orders SET amount = ? WHERE id = ?", null, List.of(20, 1)))
            .updateResult()
            .affectedRows());
    try (Connection connection = physical.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("select amount, name from orders where id = 1")) {
      assertTrue(result.next());
      assertEquals(20, result.getInt(1));
      assertEquals("测试-日本語", result.getString(2));
    }
    assertEquals(
        1,
        service
            .smartExecute(
                new FederationQueryModels.SqlConsoleRequest(
                    "ds1", "DELETE FROM ds1.orders WHERE id = ?", null, List.of(1)))
            .updateResult()
            .affectedRows());
    try (Connection connection = physical.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("select count(*) from orders")) {
      assertTrue(result.next());
      assertEquals(0, result.getInt(1));
    }
  }

  @Test
  void executeDdlShouldThrowWhenRequestIsNull() {
    FederationSqlConsoleServiceImpl service = service();
    assertThrows(IllegalArgumentException.class, () -> service.executeDdl(null));
  }

  @Test
  void smartExecuteShouldThrowWhenSqlIsNull() {
    FederationSqlConsoleServiceImpl service = service();
    assertThrows(
        IllegalArgumentException.class,
        () -> service.smartExecute(new FederationQueryModels.SqlConsoleRequest("ds1", null)));
  }

  @Test
  void smartExecuteShouldFlushCalciteSchemaCache() {
    when(catalogAssembler.flushSchemaCache()).thenReturn(2L);
    FederationSqlConsoleServiceImpl service = service();

    FederationQueryModels.SqlExecuteResult result =
        service.smartExecute(
            new FederationQueryModels.SqlConsoleRequest(null, " -- maintenance\n FLUSH CACHE; "));

    assertEquals("FLUSH_CACHE", result.type());
    assertTrue(result.message().contains("Calcite Schema 缓存清除 2 条"));
    verify(catalogAssembler).flushSchemaCache();
  }

  @Test
  void statementClassificationShouldSkipNestedLeadingComments() {
    assertEquals(
        "DELETE FROM ds1.orders",
        FederationSqlAnalysisUtils.stripLeadingComments(
            " -- first\n /* outer /* nested */ comment */ DELETE FROM ds1.orders"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/*!50000 DELETE FROM secret */ DELETE FROM ds1.orders",
        "/*M! DELETE FROM secret */ DELETE FROM ds1.orders",
        "DELETE FROM ds1.orders; DELETE FROM ds1.customers",
        "UPDATE ds1.orders SET name = 'a\\b'",
        "UPDATE ds1.orders GIBBERISH"
      })
  void rejectedWritesAreAuditedBeforeAnyConnectionOrCalciteAssembly(String sql) {
    var failure =
        assertThrows(
            FederationSqlWriteException.class,
            () -> service().smartExecute(new FederationQueryModels.SqlConsoleRequest("ds1", sql)));
    assertEquals(FederationSqlWriteException.Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
    verifyNoInteractions(dataSourceService, catalogAssembler, queryEngine);
    verify(auditService)
        .create(
            argThat(
                (FederationQueryAudit audit) ->
                    "FAILED".equals(audit.getStatus())
                        && "SQL_WRITE_UNSUPPORTED".equals(audit.getErrorMessage())));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DROP TABLE ds1.orders; DROP TABLE ds1.customers",
        "/*!50000 DROP TABLE secret */ DROP TABLE ds1.orders",
        "/*M! DROP TABLE secret */ DROP TABLE ds1.orders",
        "CREATE TABLE ds1.t (id INT) GIBBERISH",
        "ALTER TABLE ds1.t ENABLE TRIGGER ALL",
        "CREATE TABLE ds1.t AS DELETE FROM secret"
      })
  void ddlCannotUseStackedStatementsOrExecutableComments(String sql) {
    assertThrows(
        FederationSqlWriteException.class,
        () -> service().smartExecute(new FederationQueryModels.SqlConsoleRequest("ds1", sql)));
    verifyNoInteractions(dataSourceService, catalogAssembler, queryEngine);
    verify(auditService).create(any(FederationQueryAudit.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ALTER TABLE ds1.orders RENAME TO ds2.orders",
        "CREATE INDEX ds1.ix ON ds2.orders (id)",
        "CREATE TABLE ds1.orders (id INT REFERENCES ds2.customers(id))",
        "CREATE VIEW ds1.v AS SELECT * FROM ds2.orders",
        "DROP TABLE ds1.orders, ds2.customers"
      })
  void ddlRejectsEveryCrossSourceObjectBeforeOpeningConnection(String sql) {
    var first = enabledDataSource("ds-1", "ds1");
    var second = enabledDataSource("ds-2", "ds2");
    when(dataSourceService.findActiveByCode("ds1")).thenReturn(java.util.Optional.of(first));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(first, second));
    var failure =
        assertThrows(
            FederationSqlWriteException.class,
            () -> service().executeDdl(new FederationQueryModels.SqlConsoleRequest("ds1", sql)));
    assertEquals(FederationSqlWriteException.Code.SQL_WRITE_SCOPE_INVALID, failure.errorCode());
    verify(dataSourceService, never()).requireSimpleDataSource(any());
    verifyNoInteractions(catalogAssembler, queryEngine);
    verify(auditService).create(any(FederationQueryAudit.class));
  }

  @Test
  void ddlCreatesAltersRenamesAndDropsObjectsOnRealConnection() throws Exception {
    // H2 checks routing only; native ownership is covered by the real PostgreSQL/MySQL suite.
    when(writeOwnership.openNative(any(), any(FederationDdlPlan.class), eq("ds1")))
        .thenReturn(mock(FederationNativeWriteScope.class));
    var physical = new JdbcDataSource();
    physical.setURL("jdbc:h2:mem:federation-ddl-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    physical.setUser("sa");
    var source = enabledDataSource("ds-1", "ds1");
    when(dataSourceService.findActiveByCode("ds1")).thenReturn(java.util.Optional.of(source));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(source));
    when(dataSourceService.requireSimpleDataSource("ds-1"))
        .thenReturn(new org.simplepoint.data.datasource.jdbc.SimpleDataSource(physical));
    var console = service();
    for (var sql :
        List.of(
            "CREATE TABLE ds1.customers (id INT PRIMARY KEY)",
            "CREATE TABLE ds1.orders (id INT PRIMARY KEY, customer_id INT REFERENCES"
                + " ds1.customers(id))",
            "ALTER TABLE ds1.orders ADD COLUMN name VARCHAR(100)",
            "CREATE INDEX ds1.orders_ix ON ds1.orders (customer_id)",
            "COMMENT ON COLUMN ds1.orders.name IS '名称'",
            "ALTER TABLE ds1.orders RENAME TO renamed_orders",
            "CREATE VIEW ds1.order_view AS SELECT * FROM ds1.renamed_orders")) {
      console.executeDdl(new FederationQueryModels.SqlConsoleRequest("ds1", sql));
    }
    try (var connection = physical.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("INSERT INTO customers VALUES (1)");
      statement.execute("INSERT INTO renamed_orders VALUES (1, 1, '日本語')");
      assertThrows(
          java.sql.SQLException.class,
          () -> statement.execute("INSERT INTO renamed_orders VALUES (2, 99, 'invalid')"));
      try (var rows = statement.executeQuery("SELECT name FROM order_view")) {
        assertTrue(rows.next());
        assertEquals("日本語", rows.getString(1));
      }
    }
    for (var sql :
        List.of(
            "DROP VIEW ds1.order_view",
            "TRUNCATE TABLE ds1.renamed_orders",
            "DROP INDEX ds1.orders_ix",
            "DROP TABLE ds1.renamed_orders, ds1.customers")) {
      console.executeDdl(new FederationQueryModels.SqlConsoleRequest("ds1", sql));
    }
    try (var connection = physical.getConnection();
        var statement = connection.createStatement()) {
      assertThrows(
          java.sql.SQLException.class,
          () -> statement.executeQuery("SELECT * FROM renamed_orders"));
    }
    verifyNoInteractions(catalogAssembler, queryEngine);
  }

  @Test
  void mergeExecutesBothInsertAndUpdateOnNativeConnection() throws Exception {
    when(writeOwnership.openNative(any(), any(FederationDmlPlan.class), eq("ds1")))
        .thenReturn(mock(FederationNativeWriteScope.class));
    var physical = new JdbcDataSource();
    physical.setURL("jdbc:h2:mem:federation-merge-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    physical.setUser("sa");
    try (var connection = physical.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE \"Orders\" (id int primary key, name varchar(100))");
      statement.execute("CREATE TABLE \"Incoming\" (id int primary key, name varchar(100))");
      statement.execute("INSERT INTO \"Orders\" VALUES (1, 'old')");
      statement.execute("INSERT INTO \"Incoming\" VALUES (1, '更新'), (2, '日本語')");
    }
    var source = enabledDataSource("ds-1", "ds1");
    when(dataSourceService.findActiveByCode("ds1")).thenReturn(java.util.Optional.of(source));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(source));
    when(dataSourceService.requireSimpleDataSource("ds-1"))
        .thenReturn(new org.simplepoint.data.datasource.jdbc.SimpleDataSource(physical));
    var result =
        service()
            .smartExecute(
                new FederationQueryModels.SqlConsoleRequest(
                    "ds1",
                    "MERGE INTO ds1.\"Orders\" AS t USING ds1.\"Incoming\" AS s ON t.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET name = s.name "
                        + "WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name)"));
    assertEquals(2, result.updateResult().affectedRows());
    verifyNoInteractions(catalogAssembler, queryEngine);
    try (var connection = physical.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT name FROM \"Orders\" ORDER BY id")) {
      assertTrue(rows.next());
      assertEquals("更新", rows.getString(1));
      assertTrue(rows.next());
      assertEquals("日本語", rows.getString(1));
    }
  }

  @Test
  void crossSourceMergeIsRejectedBeforeOpeningTargetConnection() {
    var source = enabledDataSource("ds-1", "ds1");
    var other = enabledDataSource("ds-2", "ds2");
    when(dataSourceService.findActiveByCode("ds1")).thenReturn(java.util.Optional.of(source));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(source, other));
    var failure =
        assertThrows(
            FederationSqlWriteException.class,
            () ->
                service()
                    .executeUpdate(
                        new FederationQueryModels.SqlConsoleRequest(
                            "ds1",
                            "MERGE INTO ds1.orders AS t USING ds2.incoming AS s ON t.id = s.id "
                                + "WHEN MATCHED THEN UPDATE SET name = s.name")));
    assertEquals(FederationSqlWriteException.Code.SQL_WRITE_SCOPE_INVALID, failure.errorCode());
    verify(dataSourceService, never()).requireSimpleDataSource(any());
    verifyNoInteractions(catalogAssembler, queryEngine);
    verify(auditService)
        .create(
            argThat(
                (FederationQueryAudit audit) ->
                    "SQL_WRITE_SCOPE_INVALID".equals(audit.getErrorMessage())));
  }

  @Test
  void auditFailureDoesNotReplaceSafeRejectionOrPermitExecution() {
    var auditFailure = new IllegalStateException("private SQL audit diagnostic");
    when(auditService.create(any(FederationQueryAudit.class))).thenThrow(auditFailure);
    var failure =
        assertThrows(
            FederationSqlWriteException.class,
            () ->
                service()
                    .executeUpdate(
                        new FederationQueryModels.SqlConsoleRequest(
                            "ds1", "DELETE FROM ds1.orders; DELETE FROM ds1.customers")));
    assertEquals(FederationSqlWriteException.Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
    assertEquals(auditFailure, failure.getSuppressed()[0]);
    verifyNoInteractions(dataSourceService, catalogAssembler, queryEngine);
  }

  private boolean usesQuotedQualifiedName(final CalciteQueryRequest request) {
    return request != null
        && request.sql() != null
        && request.sql().contains("\"pg\".\"reporting\".\"orders\"");
  }
}
