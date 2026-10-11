package org.simplepoint.plugin.rbac.core.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceType;

/** Real scalar queries, including overflow after the 100th parent; no mocked paging provider. */
class CurrentUserAccessReadQueryTest {
  SessionFactory factory; Session em;
  @BeforeEach void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:profile-frontier-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .applySetting("hibernate.generate_statistics", true).build())
        .addAnnotatedClass(Resource.class).addAnnotatedClass(TenantResource.class).buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
  }
  void child(boolean custom, String id, String parent) {
    if (custom) {
      var row = new TenantResource(); row.setId(id); row.setCode(id); row.setTenantId("tenant-a"); row.setParentId(parent);
      row.setName("Actual " + id); row.setEnabled(true); row.setType(ResourceType.ACTION); em.persist(row);
    } else {
      var row = new Resource(); row.setId(id); row.setCode(id); row.setParentId(parent); row.setName("Actual " + id);
      row.setDisabled(false); row.setType(ResourceType.ACTION); row.setPermissionMetadata("Must not hydrate internal configuration"); em.persist(row);
    }
  }
  void evidence() {em.flush(); em.clear(); factory.getStatistics().clear();}
  List<String> parents() {var ids = new ArrayList<String>(); for (int i = 0; i <= 100; i++) ids.add("parent-" + String.format("%03d", i)); return ids;}
  List<CurrentUserAccessReadQuery.Edge> read(boolean custom, int budget) {return new CurrentUserAccessReadQuery(em).children(parents(), custom ? "tenant-a" : null, budget);}
  void bounded(long queries) {assertEquals(0, factory.getStatistics().getEntityLoadCount()); assertEquals(queries, factory.getStatistics().getPrepareStatementCount());}
  @AfterEach void cleanup() {if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();} if (factory != null) factory.close();}

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void firstOverflowStopsBeforeUnrelatedSiblingPagesAndLaterParentBatches(boolean custom) {
    for (int i = 0; i < 521; i++) child(custom, "child-" + String.format("%03d", i), "parent-000");
    child(custom, "later-parent-child", "parent-100"); evidence();
    var rows = read(custom, 250); assertEquals(251, rows.size());
    assertTrue(rows.stream().noneMatch(row -> row.id().equals("later-parent-child"))); bounded(3);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void exactBudgetMustCheckLaterParentBatchForAnOverflowWitness(boolean custom) {
    for (int i = 0; i < 250; i++) child(custom, "child-" + String.format("%03d", i), "parent-000");
    child(custom, "later-parent-child", "parent-100"); evidence();
    var rows = read(custom, 250); assertEquals(251, rows.size()); assertEquals("later-parent-child", rows.getLast().id()); bounded(4);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void exactBudgetWithoutOverflowKeepsAllRowsAndChecksTheRemainingBatch(boolean custom) {
    for (int i = 0; i < 250; i++) child(custom, "child-" + String.format("%03d", i), "parent-000"); evidence();
    assertEquals(250, read(custom, 250).size()); bounded(4);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void exhaustedBudgetStillChecksExactlyOneNode(boolean custom) {
    child(custom, "first", "parent-000"); child(custom, "second", "parent-000"); evidence();
    assertEquals(1, read(custom, 0).size()); bounded(1);
  }

  @Test void negativeBudgetIsRejectedBeforeSql() {evidence(); assertThrows(IllegalArgumentException.class, () -> read(false, -1)); bounded(0);}
}
