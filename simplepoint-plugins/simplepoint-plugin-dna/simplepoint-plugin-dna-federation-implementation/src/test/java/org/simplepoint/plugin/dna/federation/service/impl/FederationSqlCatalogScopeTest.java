package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FederationSqlCatalogScopeTest {

  @Test
  void scopesExplicitCatalogsToTheirOwnSources() {
    assertEquals(Map.of("ds1", Set.of("main_db"), "ds2", Set.of("audit_db")),
        FederationSqlAnalysisUtils.requestedCatalogs(
            "select * from ds1.main_db.public.orders a join ds2.audit_db.public.users b on a.id=b.id",
            null, "ds1", List.of("ds1", "ds2")));
  }

  @Test
  void usesSelectedSourceForUnqualifiedPathsAndQuotedDefaultSchema() {
    assertEquals(Map.of("ds1", Set.of("main.db", "audit_db")),
        FederationSqlAnalysisUtils.requestedCatalogs(
            "select * from audit_db.public.orders",
            "ds1.\"main.db\".public", "ds1", List.of("ds1")));
    assertEquals(Map.of(), FederationSqlAnalysisUtils.requestedCatalogs(
        "select 1", null, "ds1", List.of()));
  }
}
