package org.simplepoint.data.jpa.base.repository;

import static org.assertj.core.api.Assertions.*;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.Set;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.datascopeannotation.DataScopeContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

class DataScopeDatabaseTest {
  @Entity(name = "PermissionTestRow")
  @Table(name = "permission_test_row")
  public static class Row extends TenantBaseEntityImpl<String> {
    private String name = "allowed";
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
  }

  private SessionFactory factory;
  private Session session;
  private BaseServiceImpl<BaseRepositoryImpl<Row, String>, Row, String> service;
  private String ownId;
  private String otherId;
  private String foreignId;

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:scope-" + java.util.UUID.randomUUID())
        .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(Row.class).buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    ownId = row("tenant-a", "user-a", "dept-a");
    otherId = row("tenant-a", "user-b", "dept-b");
    foreignId = row("tenant-b", "user-a", "dept-a");
    session.flush();
    session.clear();
    service = new BaseServiceImpl<>(new BaseRepositoryImpl<>(Row.class, session), null) {
      @Override protected Set<String> getAllFieldNames(Class<Row> type) { return new java.util.HashSet<>(Set.of("name")); }
    };
  }

  private String row(String tenant, String user, String dept) {
    Row row = new Row();
    row.setTenantId(tenant);
    row.setCreatedBy(user);
    row.setCreateOrgDeptId(dept);
    session.persist(row);
    return row.getId();
  }

  private void scope(String type, Set<String> departments, boolean self) {
    AuthorizationContext context = new AuthorizationContext();
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setUserId("user-a");
    if (type != null) context.setDataScopeType(type);
    context.setDeptIds(departments);
    context.setDataScopeIncludeSelf(self);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    DataScopeContext.clear();
    org.simplepoint.core.ResourcePolicyContext.clear();
    if (session != null) {
      if (session.getTransaction().isActive()) session.getTransaction().rollback();
      session.close();
    }
    if (factory != null) factory.close();
  }

  @Test
  void primaryKeyReadsUpdatesAndDeletesCannotCrossTenantEvenWithAllScope() {
    scope("ALL", Set.of(), false);
    // EntityManager.find can load another tenant; the service guard must still deny it.
    assertThat(session.find(Row.class, foreignId)).isNotNull();
    assertThat(service.findById(foreignId)).isEmpty();
    assertThat(service.existsById(foreignId)).isFalse();
    Row incoming = new Row();
    incoming.setId(foreignId);
    incoming.setTenantId("tenant-a");
    assertThatThrownBy(() -> service.modifyById(incoming)).isInstanceOf(java.util.NoSuchElementException.class);
    service.removeByIds(Set.of(foreignId));
    session.clear();
    assertThat(session.find(Row.class, foreignId)).isNotNull();
    assertThat(service.findAll(Map.of())).extracting(Row::getId).containsExactlyInAnyOrder(ownId, otherId);
  }

  @Test
  void selfScopeRestrictsPageTotalCountAndPrimaryKeyConsistently() {
    scope("SELF", Set.of(), false);
    var page = service.limit(Map.of(), PageRequest.of(0, 1));
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent()).extracting(Row::getId).containsExactly(ownId);
    assertThat(service.count(new Row())).isEqualTo(1);
    assertThat(service.findById(otherId)).isEmpty();
    assertThatThrownBy(service::removeAll).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void customScopeUnionsSelfWithoutCrossingTenant() {
    scope("CUSTOM", Set.of("dept-b"), true);
    assertThat(service.findAll(Map.of())).extracting(Row::getId).containsExactlyInAnyOrder(ownId, otherId);
    scope("CUSTOM", Set.of(), false);
    assertThat(service.count(new Row())).isZero();
    assertThat(service.findAll(Map.of())).isEmpty();
  }

  private void expression(String text) {
    scope("ALL", Set.of(), false);
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    context.setResourceDataScopes(Map.of("test.view", new org.simplepoint.core.ResourceDataScope("DENY", Set.of(), false, java.util.List.of(text))));
    org.simplepoint.core.ResourcePolicyContext.set("test.view");
  }

  @Test
  void expressionSharesTenantBoundaryCountReadsAndSqlNullLogic() {
    expression("createdBy = $user.id OR createOrgDeptId IN [\"dept-b\"]");
    assertThat(service.limit(Map.of(), PageRequest.of(0, 1)).getTotalElements()).isEqualTo(2);
    assertThat(service.findById(foreignId)).isEmpty();
    assertThat(service.findById(otherId)).isPresent();
    expression("NOT (createOrgDeptId = $user.organizationId)");
    assertThat(service.count(new Row())).isZero();
    assertThat(service.findById(ownId)).isEmpty();
    expression("createOrgDeptId NOT IN $user.organizationIds");
    assertThat(service.count(new Row())).isEqualTo(2);
    assertThat(service.findById(ownId)).isPresent();
  }

  @Test
  void expressionRejectsUpdatesThatMoveAnExistingRowOutsideItsScope() {
    expression("name = \"allowed\"");
    Row incoming = new Row(); incoming.setId(ownId); incoming.setName("forbidden");
    assertThatThrownBy(() -> service.modifyById(incoming)).isInstanceOf(AccessDeniedException.class);
    assertThat(session.find(Row.class, ownId).getName()).isEqualTo("allowed");
  }

  @Test
  void expressionCannotGrantAnUnknownFieldOrWidenOnMissingContext() {
    expression("removedField IS NULL");
    assertThatThrownBy(() -> service.findById(ownId)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.count(new Row())).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void missingPolicyNeverFallsBackToAll() {
    scope(null, Set.of(), false);
    assertThat(service.findById(ownId)).isEmpty();
    assertThat(service.findAll(Map.of())).isEmpty();
    assertThat(service.count(new Row())).isZero();
    assertThatThrownBy(service::removeAll).isInstanceOf(AccessDeniedException.class);
  }
}
