package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.data.calcite.core.query.CalciteQueryAnalysis;
import org.simplepoint.data.calcite.core.query.CalciteQueryColumn;
import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryPolicyRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.simplepoint.plugin.dna.federation.service.support.FederationCalciteCatalogAssembler;

@ExtendWith(MockitoExtension.class)
class FederationSqlConsoleServiceImplTest {

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @Mock
  private FederationQueryPolicyRepository policyRepository;

  @Mock
  private FederationQueryAuditService auditService;

  @Mock
  private FederationCalciteCatalogAssembler catalogAssembler;

  @Mock
  private CalciteQueryEngine queryEngine;

  @Test
  void executeShouldNotMountAnyDatasourceForLocalQuery() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "ds1");
    FederationQueryPolicy policy = enabledPolicy("ds-1", true);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "ds1",
            List.of(),
            rootSchema -> {
            }
        );
    CalciteQueryAnalysis analysis = new CalciteQueryAnalysis("EnumerableValues(tuples=[[{ 1 }]])", List.of(), false);
    CalciteQueryResult queryResult = new CalciteQueryResult(
        List.of(new CalciteQueryColumn("EXPR$0", "INTEGER")),
        List.of(List.of(1)),
        false,
        1,
        3L,
        analysis
    );
    when(policyRepository.findAllActiveByCatalogId("ds-1")).thenReturn(List.of(policy));
    when(catalogAssembler.assemble(eq("ds1"), argThat((List<JdbcDataSourceDefinition> definitions) -> definitions.isEmpty())))
        .thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    when(queryEngine.execute(any(), any(), any())).thenReturn(queryResult);
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(invocation -> invocation.getArgument(0));
    FederationSqlConsoleServiceImpl service = service();

    when(dataSourceService.findActiveById("ds-1")).thenReturn(java.util.Optional.of(dataSource));

    FederationQueryModels.SqlQueryResult response = service.execute("ds-1", new FederationQueryModels.SqlConsoleRequest(
        "ds1",
        "select 1"
    ));

    assertEquals("ds1", response.catalogCode());
    assertTrue(response.dataSources().isEmpty());
    verify(dataSourceService, never()).listEnabledDefinitions();
    verify(catalogAssembler).assemble(eq("ds1"), argThat((List<JdbcDataSourceDefinition> definitions) -> definitions.isEmpty()));
  }

  @Test
  void executeShouldRejectCrossSourceJoinWhenPolicyDisallowsIt() {
    JdbcDataSourceDefinition selected = enabledDataSource("ds-1", "ds1");
    JdbcDataSourceDefinition other = enabledDataSource("ds-2", "ds2");
    FederationQueryPolicy policy = enabledPolicy("ds-1", false);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "ds1",
            List.of("ds1", "ds2"),
            rootSchema -> {
            }
        );
    CalciteQueryAnalysis analysis = new CalciteQueryAnalysis(
        """
            EnumerableHashJoin(condition=[=($0, $2)], joinType=[inner])
              JdbcTableScan(table=[[ds1, PUBLIC, ORDERS]])
              JdbcTableScan(table=[[ds2, PUBLIC, CUSTOMERS]])
            """,
        List.of("Filter"),
        true
    );
    when(policyRepository.findAllActiveByCatalogId("ds-1")).thenReturn(List.of(policy));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected, other));
    when(catalogAssembler.assemble(eq("ds1"), argThat(definitions ->
        definitions.size() == 2
            && definitions.stream().map(JdbcDataSourceDefinition::getCode).toList().containsAll(List.of("ds1", "ds2"))
    ))).thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(invocation -> invocation.getArgument(0));
    FederationSqlConsoleServiceImpl service = service();

    when(dataSourceService.findActiveById("ds-1")).thenReturn(java.util.Optional.of(selected));

    IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.execute(
        "ds-1",
        new FederationQueryModels.SqlConsoleRequest(
            "ds1",
            "select * from ds1.orders join ds2.customers on ds1.orders.customer_id = ds2.customers.id"
        )
    ));

    assertTrue(exception.getMessage().contains("禁止跨数据源 Join"));
    verify(queryEngine, never()).execute(any(), any());
    verify(auditService).create(argThat((FederationQueryAudit audit) ->
        "REJECTED".equals(audit.getStatus())
            && "ds1".equals(audit.getCatalogCode())
            && audit.getErrorMessage() != null
    ));
  }

  @Test
  void executeShouldReturnResultAndWriteSuccessAudit() {
    JdbcDataSourceDefinition selected = enabledDataSource("ds-1", "ds1");
    JdbcDataSourceDefinition other = enabledDataSource("ds-2", "ds2");
    FederationQueryPolicy policy = enabledPolicy("ds-1", true);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "ds1",
            List.of("ds1", "ds2"),
            rootSchema -> {
            }
        );
    CalciteQueryAnalysis analysis = new CalciteQueryAnalysis(
        """
            EnumerableHashJoin(condition=[=($0, $2)], joinType=[inner])
              JdbcFilter(condition=[>($2, 0)])
                JdbcTableScan(table=[[ds1, PUBLIC, ORDERS]])
              JdbcTableScan(table=[[ds2, PUBLIC, CUSTOMERS]])
            """,
        List.of("Filter"),
        true
    );
    CalciteQueryResult queryResult = new CalciteQueryResult(
        List.of(
            new CalciteQueryColumn("id", "INTEGER"),
            new CalciteQueryColumn("name", "VARCHAR")
        ),
        List.of(List.of(1, "Alice")),
        false,
        1,
        42L,
        analysis
    );
    when(policyRepository.findAllActiveByCatalogId("ds-1")).thenReturn(List.of(policy));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected, other));
    when(catalogAssembler.assemble(eq("ds1"), argThat(definitions ->
        definitions.size() == 2
            && definitions.stream().map(JdbcDataSourceDefinition::getCode).toList().containsAll(List.of("ds1", "ds2"))
    ))).thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    when(queryEngine.execute(any(), any(), any())).thenReturn(queryResult);
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(invocation -> invocation.getArgument(0));
    FederationSqlConsoleServiceImpl service = service();

    when(dataSourceService.findActiveById("ds-1")).thenReturn(java.util.Optional.of(selected));

    FederationQueryModels.SqlQueryResult response = service.execute("ds-1", new FederationQueryModels.SqlConsoleRequest(
        "ds1",
        "select o.id, c.name from ds1.orders o join ds2.customers c on o.customer_id = c.id"
    ));

    assertEquals("ds1", response.catalogCode());
    assertEquals("policy-demo", response.policyCode());
    assertEquals(2, response.dataSources().size());
    assertTrue(response.crossSourceJoin());
    assertEquals(1, response.returnedRows());
    assertEquals("Alice", response.rows().get(0).get(1));
    assertTrue(response.pushdownSummary().contains("命中数据源"));
    verify(auditService).create(argThat((FederationQueryAudit audit) ->
        "SUCCESS".equals(audit.getStatus())
            && Long.valueOf(1L).equals(audit.getResultRows())
            && audit.getErrorMessage() == null
    ));
  }

  @Test
  void executeShouldNormalizeQualifiedIdentifiersForJdbcQueries() {
    JdbcDataSourceDefinition selected = enabledDataSource("pg-1", "pg");
    FederationQueryPolicy policy = enabledPolicy("pg-1", true);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "pg",
            List.of("pg"),
            rootSchema -> {
            }
        );
    CalciteQueryAnalysis analysis = new CalciteQueryAnalysis(
        """
            JdbcTableScan(table=[[pg, reporting, ORDERS]])
            """,
        List.of(),
        false
    );
    CalciteQueryResult queryResult = new CalciteQueryResult(
        List.of(new CalciteQueryColumn("total", "INTEGER")),
        List.of(List.of(1)),
        false,
        1,
        8L,
        analysis
    );
    when(policyRepository.findAllActiveByCatalogId("pg-1")).thenReturn(List.of(policy));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected));
    when(catalogAssembler.assemble(eq("pg"), argThat(definitions ->
        definitions.size() == 1
            && definitions.stream().map(JdbcDataSourceDefinition::getCode).toList().contains("pg")
    ))).thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    when(queryEngine.execute(any(), any(), any())).thenReturn(queryResult);
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(invocation -> invocation.getArgument(0));
    FederationSqlConsoleServiceImpl service = service();

    when(dataSourceService.findActiveById("pg-1")).thenReturn(java.util.Optional.of(selected));

    FederationQueryModels.SqlQueryResult response = service.execute("pg-1", new FederationQueryModels.SqlConsoleRequest(
        "pg",
        "select count(*) from pg.reporting.orders"
    ));

    ArgumentCaptor<CalciteQueryRequest> explainRequest = ArgumentCaptor.forClass(CalciteQueryRequest.class);
    ArgumentCaptor<CalciteQueryRequest> executeRequest = ArgumentCaptor.forClass(CalciteQueryRequest.class);
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
        new org.simplepoint.plugin.dna.federation.service.support.FederationMetadataCacheService()
    );
  }

  private static JdbcDataSourceDefinition enabledDataSource(final String id, final String code) {
    JdbcDataSourceDefinition dataSource = new JdbcDataSourceDefinition();
    dataSource.setId(id);
    dataSource.setCode(code);
    dataSource.setName(code.toUpperCase());
    dataSource.setEnabled(true);
    return dataSource;
  }

  private static FederationQueryPolicy enabledPolicy(final String dataSourceId, final boolean allowCrossSourceJoin) {
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

  private boolean usesQuotedQualifiedName(final CalciteQueryRequest request) {
    return request != null
        && request.sql() != null
        && request.sql().contains("\"pg\".\"reporting\".\"orders\"");
  }
}
