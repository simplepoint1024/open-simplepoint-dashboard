package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.data.calcite.core.query.CalciteQueryAnalysis;
import org.simplepoint.data.calcite.core.query.CalciteQueryColumn;
import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryPolicyRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationCatalogService;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.simplepoint.plugin.dna.federation.service.support.FederationCalciteCatalogAssembler;

@ExtendWith(MockitoExtension.class)
class FederationSqlConsoleServiceImplTest {

  @Mock
  private FederationCatalogService catalogService;

  @Mock
  private FederationQueryPolicyRepository policyRepository;

  @Mock
  private FederationQueryAuditService auditService;

  @Mock
  private FederationCalciteCatalogAssembler catalogAssembler;

  @Mock
  private CalciteQueryEngine queryEngine;

  @Test
  void executeShouldRejectCrossSourceJoinWhenPolicyDisallowsIt() {
    FederationCatalog catalog = enabledCatalog();
    FederationQueryPolicy policy = enabledPolicy(false);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "demo",
            List.of("ds1", "ds2"),
            rootSchema -> {
            }
        );
    CalciteQueryAnalysis analysis = new CalciteQueryAnalysis(
        """
            EnumerableHashJoin(condition=[=($0, $2)], joinType=[inner])
              JdbcTableScan(table=[[demo, ds1, PUBLIC, ORDERS]])
              JdbcTableScan(table=[[demo, ds2, PUBLIC, CUSTOMERS]])
            """,
        List.of("Filter"),
        true
    );
    when(catalogService.findActiveByCode("demo")).thenReturn(Optional.of(catalog));
    when(policyRepository.findAllActiveByCatalogId("catalog-1")).thenReturn(List.of(policy));
    when(catalogAssembler.assemble(catalog)).thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(invocation -> invocation.getArgument(0));
    FederationSqlConsoleServiceImpl service = new FederationSqlConsoleServiceImpl(
        catalogService,
        policyRepository,
        auditService,
        catalogAssembler,
        queryEngine
    );

    IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.execute(
        new FederationQueryModels.SqlConsoleRequest(
            "demo",
            "select * from ds1.orders join ds2.customers on ds1.orders.customer_id = ds2.customers.id"
        )
    ));

    assertTrue(exception.getMessage().contains("禁止跨数据源 Join"));
    verify(queryEngine, never()).execute(any(), any());
    verify(auditService).create(argThat((FederationQueryAudit audit) ->
        "REJECTED".equals(audit.getStatus())
            && "demo".equals(audit.getCatalogCode())
            && audit.getErrorMessage() != null
    ));
  }

  @Test
  void executeShouldReturnResultAndWriteSuccessAudit() {
    FederationCatalog catalog = enabledCatalog();
    FederationQueryPolicy policy = enabledPolicy(true);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly =
        new FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly(
            "demo",
            List.of("ds1", "ds2"),
            rootSchema -> {
            }
        );
    CalciteQueryAnalysis analysis = new CalciteQueryAnalysis(
        """
            EnumerableHashJoin(condition=[=($0, $2)], joinType=[inner])
              JdbcFilter(condition=[>($2, 0)])
                JdbcTableScan(table=[[demo, ds1, PUBLIC, ORDERS]])
              JdbcTableScan(table=[[demo, ds2, PUBLIC, CUSTOMERS]])
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
    when(catalogService.findActiveByCode("demo")).thenReturn(Optional.of(catalog));
    when(policyRepository.findAllActiveByCatalogId("catalog-1")).thenReturn(List.of(policy));
    when(catalogAssembler.assemble(catalog)).thenReturn(assembly);
    when(queryEngine.explain(any(), any())).thenReturn(analysis);
    when(queryEngine.execute(any(), any())).thenReturn(queryResult);
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(invocation -> invocation.getArgument(0));
    FederationSqlConsoleServiceImpl service = new FederationSqlConsoleServiceImpl(
        catalogService,
        policyRepository,
        auditService,
        catalogAssembler,
        queryEngine
    );

    FederationQueryModels.SqlQueryResult response = service.execute(new FederationQueryModels.SqlConsoleRequest(
        "demo",
        "select o.id, c.name from ds1.orders o join ds2.customers c on o.customer_id = c.id"
    ));

    assertEquals("demo", response.catalogCode());
    assertEquals("policy-demo", response.policyCode());
    assertEquals(2, response.dataSources().size());
    assertTrue(response.crossSourceJoin());
    assertEquals(1, response.returnedRows());
    assertEquals("Alice", response.rows().get(0).get(1));
    assertTrue(response.pushedSqls().isEmpty());
    assertTrue(response.pushdownSummary().contains("命中数据源"));
    verify(auditService).create(argThat((FederationQueryAudit audit) ->
        "SUCCESS".equals(audit.getStatus())
            && Long.valueOf(1L).equals(audit.getResultRows())
            && audit.getErrorMessage() == null
    ));
  }

  private static FederationCatalog enabledCatalog() {
    FederationCatalog catalog = new FederationCatalog();
    catalog.setId("catalog-1");
    catalog.setCode("demo");
    catalog.setName("Demo");
    catalog.setEnabled(true);
    return catalog;
  }

  private static FederationQueryPolicy enabledPolicy(final boolean allowCrossSourceJoin) {
    FederationQueryPolicy policy = new FederationQueryPolicy();
    policy.setId("policy-1");
    policy.setCatalogId("catalog-1");
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
}
