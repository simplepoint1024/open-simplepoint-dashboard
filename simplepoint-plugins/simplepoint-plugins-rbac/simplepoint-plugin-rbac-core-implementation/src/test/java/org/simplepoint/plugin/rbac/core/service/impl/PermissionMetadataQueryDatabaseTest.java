package org.simplepoint.plugin.rbac.core.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.permission.*;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceType;

/** Genuine resource SQL/registration/contracts; no repository stub, prefiltered rows or synthetic provider. */
class PermissionMetadataQueryDatabaseTest {
  SessionFactory factory; Session em; AuthorizationContext context;
  PermissionExpressionService expressions; FieldScopeCatalog fields;
  final List<String> statements = new ArrayList<>();
  final String metadata = new PermissionPageDescriptor(List.of(PermissionModelDescriptor.describe(Organization.class))).toJson();

  @BeforeEach void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:permission-metadata-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .applySetting("hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {statements.add(sql); return sql;})
        .build()).addAnnotatedClass(Resource.class).buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction(); context = new AuthorizationContext();
    context.setUserId("verified-caller"); context.setResources(new HashSet<>());
    context.setAttributes(Map.of("X-Tenant-Id", "metadata-tenant"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    expressions = new PermissionExpressionService(em); fields = new FieldScopeCatalog(em);
    for (int index = 0; index < 521; index++) {String code = "page-" + String.format("%03d", index); register(code, metadata); context.getResources().add(code);}
    for (int index = 0; index < 777; index++) register("unrelated-" + index, "malformed unrelated metadata");
    register("disabled", "malformed disabled metadata").setDisabled(true); context.getResources().add("disabled");
    register("deleted", "malformed deleted metadata").setDeletedAt(java.time.Instant.now()); context.getResources().add("deleted");
    register("group", "malformed group metadata").setType(ResourceType.GROUP); context.getResources().add("group");
    em.flush(); em.clear(); resetEvidence();
  }
  Resource register(String code, String json) {
    var resource = new Resource(); resource.setId(code); resource.setCode(code); resource.setName(code);
    resource.setType(ResourceType.PAGE); resource.setDisabled(false); resource.setPermissionMetadata(json); em.persist(resource); return resource;
  }
  void resetEvidence() {statements.clear(); factory.getStatistics().clear();}
  long rows() {return Arrays.stream(factory.getStatistics().getQueries()).mapToLong(query -> factory.getStatistics().getQueryStatistics(query).getExecutionRowCount()).sum();}
  @AfterEach void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
  }
  @Test void completeExpressionCatalogReadsOnlyGrantedMetadataInBoundedBatches() {
    var result = expressions.catalog();
    assertEquals(521, result.size()); assertTrue(result.containsKey("page-520"), "A query bound is not a catalog truncation");
    assertEquals(521, rows(), "Unrelated resource metadata must never enter the SQL result");
    assertEquals(6, factory.getStatistics().getQueryExecutionCount()); assertEquals(0, factory.getStatistics().getEntityLoadCount());
    assertTrue(statements.stream().filter(sql -> sql.startsWith("select")).allMatch(sql -> sql.contains("code in") && sql.contains("fetch first ? rows only")));
    for (String sql : statements) {
      var parameters = java.util.regex.Pattern.compile("\\bcode\\s+in\\s*\\(([^)]*)\\)").matcher(sql);
      if (parameters.find()) assertTrue(parameters.group(1).chars().filter(value -> value == '?').count() <= 100,
          "Unique registered code plus bounded IN and SQL limit bound each result without truncating the union");
    }
  }
  @Test void validationReadsOneTargetAndDoesNotParseAnotherGrantedPage() {
    var other = em.find(Resource.class, "page-001"); other.setPermissionMetadata("malformed granted metadata"); em.flush(); em.clear(); resetEvidence();
    var valid = expressions.validate("page-520", "name = \"正确组织\"", null);
    assertEquals("name = \"正确组织\"", valid.expression()); assertEquals(1, rows());
    assertEquals(1, factory.getStatistics().getQueryExecutionCount()); assertEquals(0, factory.getStatistics().getEntityLoadCount());
    assertTrue(statements.stream().filter(sql -> sql.startsWith("select")).allMatch(sql -> sql.contains("code=?") && sql.contains("fetch first ? rows only")));
  }
  @Test void deniedUnknownAndEmptyTargetsCannotTriggerADirectoryReadEvenForAnAdministratorFlag() {
    // AuthorizationContext resources are initialize-once; install the genuinely new authorization snapshot.
    context = new AuthorizationContext(); context.setUserId("verified-caller"); context.setIsAdministrator(true); context.setResources(Set.of());
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertTrue(expressions.catalog().isEmpty()); assertTrue(fields.pages().isEmpty());
    for (String target : Arrays.asList(null, "", " ", "page-520", "unrelated-0"))
      assertThrows(InvalidPermissionPolicyException.class, () -> expressions.validate(target, "name = \"x\"", null));
    assertTrue(fields.pages("page-520").isEmpty()); assertEquals(0, factory.getStatistics().getQueryExecutionCount()); assertTrue(statements.isEmpty());
  }
  @Test void completeFieldPresentationUsesBoundedQueriesWithoutLosingAnOffPageTarget() {
    var result = fields.pages(); assertEquals(521, result.size()); assertTrue(result.containsKey("page-520"));
    assertEquals(521, rows()); assertEquals(6, factory.getStatistics().getQueryExecutionCount());
    assertEquals(0, factory.getStatistics().getEntityLoadCount());
    assertTrue(statements.stream().filter(sql -> sql.startsWith("select")).allMatch(sql -> sql.contains("code in") && sql.contains("fetch first ? rows only")));
    resetEvidence(); assertTrue(fields.pages("page-520").containsKey("page-520")); assertEquals(1, rows());
    assertEquals(1, factory.getStatistics().getQueryExecutionCount());
  }
  @Test void blankStoredMetadataIsUnregisteredRatherThanANullPointerOrAnInventedContract() {
    register("blank", "  "); context.getResources().add("blank"); em.flush(); em.clear(); resetEvidence();
    assertEquals(521, expressions.catalog().size()); assertEquals(521, fields.pages().size());
    assertThrows(InvalidPermissionPolicyException.class, () -> expressions.validate("blank", "name = \"x\"", null));
    assertTrue(fields.pages("blank").isEmpty());
    var directory = fields.nodes(null, org.springframework.data.domain.PageRequest.of(0, 20));
    assertEquals(521, directory.getTotalElements()); assertEquals(20, directory.getNumberOfElements());
    assertTrue(directory.getContent().stream().noneMatch(node -> node.id().equals("blank")));
  }
  @Test void missingCallerCannotReadRegisteredMetadata() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertThrows(org.springframework.security.access.AccessDeniedException.class, expressions::catalog);
    assertThrows(org.springframework.security.access.AccessDeniedException.class, fields::pages);
    assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> expressions.validate("page-520", "name = \"x\"", null));
    assertTrue(statements.isEmpty());
  }
}
