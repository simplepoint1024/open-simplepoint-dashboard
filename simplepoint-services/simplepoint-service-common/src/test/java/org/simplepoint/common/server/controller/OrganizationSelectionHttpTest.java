package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.*;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.rest.controller.OrganizationController;
import org.simplepoint.plugin.rbac.tenant.service.impl.OrganizationServiceImpl;
import org.simplepoint.plugin.rbac.tenant.service.impl.OrganizationSelectionQuery;
import org.simplepoint.plugin.rbac.tenant.service.impl.OrganizationScopedReadQuery;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real read-only draft selection SQL and production HTTP converter. */
class OrganizationSelectionHttpTest {
  SessionFactory factory;
  Session em;
  MockMvc mvc;
  AuthorizationContext context;
  List<Organization> rows = new ArrayList<>();
  List<String> selectedIds;
  ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:org-selection-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").applySetting("hibernate.hbm2ddl.halt_on_error", true)
        .applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(Organization.class).buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    for (int i = 0; i < 244; i++) {
      var row = new Organization(); row.setTenantId(i == 243 ? "outside" : "tenant-a");
      row.setName("Organization" + String.format("%03d", i)); row.setCode("code-" + i); row.setType("DEPT");
      row.setDescription("Description " + i); row.setEnabled(i % 2 == 0);
      if (i == 242) row.setDeletedAt(Instant.now());
      em.persist(row); rows.add(row);
    }
    em.flush(); selectedIds = rows.subList(0, 120).stream().map(Organization::getId).toList();
    var details = mock(DetailsProviderService.class);
    var config = new JsonSchemaAutoConfiguration(mapper);
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    when(details.getDialect(JsonSchemaGenerator.class)).thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(details.getDialect(JsonSchemaDetailsService.class)).thenReturn(mock(JsonSchemaDetailsService.class));
    var queryProxy = new org.springframework.aop.framework.ProxyFactory(new OrganizationSelectionQuery(em));
    queryProxy.setProxyTargetClass(true);
    queryProxy.addAdvice(new org.springframework.dao.support.PersistenceExceptionTranslationInterceptor(
        new org.springframework.orm.jpa.vendor.HibernateJpaDialect()));
    var service = new OrganizationServiceImpl(mock(OrganizationRepository.class), details, null,
        mock(ResourceAuthorizationVersionService.class), (OrganizationSelectionQuery) queryProxy.getProxy(),
        new OrganizationScopedReadQuery(em));
    mvc = MockMvcBuilders.standaloneSetup(new OrganizationController(service)).setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter())
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
    authorize(Map.of(), "tenant-a");
  }

  void authorize(Map<String, String> fields, String tenant) {
    context = new AuthorizationContext(); context.setUserId("reader"); context.setIsAdministrator(false);
    context.setFieldPermissions(fields);
    context.setAttributes(tenant == null ? Map.of() : Map.of("X-Tenant-Id", tenant));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query(boolean selected, List<String> ids,
      Map<String, String> filters) throws Exception {
    return post("/platform/organizations/selection").contentType("application/json")
        .content(mapper.writeValueAsString(Map.of("ids", ids, "selected", selected, "filters", filters)))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    ResourcePolicyContext.clear();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
  }

  @Test
  void pageDataRuleScopesSelectionTreePickerAndCountsWithOneOrmPredicate() throws Exception {
    rows.get(1).setParentId(rows.get(0).getId());
    rows.get(2).setParentId(rows.get(1).getId());
    em.flush();
    var ids = Set.of(rows.get(1).getId(), rows.get(2).getId());
    var scope = new DataScopeCondition("CUSTOM", "id", "createdBy", "reader", ids);
    var scoped = new OrganizationScopedReadQuery(em);
    var roots = scoped.tree("tenant-a", null, Map.of(), PageRequest.of(0, 20), scope);
    assertEquals(1, roots.getTotalElements());
    assertEquals(rows.get(1).getId(), roots.getContent().getFirst().getId());
    var children = scoped.tree("tenant-a", rows.get(1).getId(), Map.of(),
        PageRequest.of(0, 20), scope);
    assertEquals(1, children.getTotalElements());
    assertEquals(rows.get(2).getId(), children.getContent().getFirst().getId());
    assertEquals(Set.of(rows.get(1).getId()),
        scoped.visibleParentsWithChildren("tenant-a", ids, scope));
    var picker = scoped.options("tenant-a", null, null, null, false,
        PageRequest.of(0, 20), scope);
    assertEquals(List.of(rows.get(1).getId()),
        picker.getContent().stream().map(Organization::getId).toList());
    assertEquals(List.of(rows.get(2).getId()),
        scoped.options("tenant-a", null, rows.get(1).getId(), null, false,
            PageRequest.of(0, 20), scope).getContent().stream().map(Organization::getId).toList());

    context.setResourceDataScopes(Map.of("organizations.view",
        new ResourceDataScope("CUSTOM", ids, false)));
    ResourcePolicyContext.set("organizations.view");
    mvc.perform(query(false, List.of(), Map.of())).andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(query(true, List.of(rows.get(0).getId(), rows.get(1).getId()), Map.of()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(rows.get(1).getId()));
    mvc.perform(get("/platform/organizations/tree").param("size", "20")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(rows.get(1).getId()))
        .andExpect(jsonPath("$.content[0].hasChildren").value(true));
    mvc.perform(get("/platform/organizations/options").param("size", "20")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value(rows.get(1).getId()));
    context.setResourceFieldPermissions(Map.of("organizations.view", Map.of(
        Organization.class.getName() + "#description", "HIDDEN",
        Organization.class.getName() + "#name", "MASKED")));
    mvc.perform(get("/platform/organizations/options").param("size", "20")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("***"))
        .andExpect(jsonPath("$.content[0].description").doesNotExist());
  }

  @Test
  void bothSidesHaveRealCountsAndPaginationBeyondOneHundred() throws Exception {
    factory.getStatistics().clear();
    mvc.perform(query(true, selectedIds, Map.of()).param("page", "5").param("size", "20"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(120))
        .andExpect(jsonPath("$.content[0].name").value("Organization100"));
    assertEquals(2, factory.getStatistics().getQueryExecutionCount(), "one page plus count, no catalog hydration");
    assertEquals(0, factory.getStatistics().getEntityUpdateCount(), "draft query must never persist a selection");
    mvc.perform(query(false, selectedIds, Map.of()).param("sort", "name,desc", "code,asc"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(122))
        .andExpect(jsonPath("$.content[0].name").value("Organization241"));
  }

  @Test
  void advancedBooleanAndHeaderFiltersIntersectDraftMembership() throws Exception {
    String ast = """
        {"version":1,"where":{"op":"and","children":[
          {"op":"or","children":[{"field":"name","op":"equals","value":"Organization119"},{"field":"name","op":"equals","value":"Organization239"}]},
          {"op":"not","children":[{"field":"enabled","op":"equals","value":true}]}]}}
        """;
    mvc.perform(query(false, selectedIds, Map.of("$query", ast, "description", "like:239")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(rows.get(239).getId()));
    mvc.perform(query(true, selectedIds, Map.of("$query", ast))).andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].name").value("Organization119"));
  }

  @Test
  void clientIdentifiersCannotAuthorizeForeignOrDeletedRowsAndAnEmptyDraftIsValid() throws Exception {
    var maliciousIds = List.of(rows.get(242).getId(), rows.get(243).getId(), "missing");
    mvc.perform(query(true, maliciousIds, Map.of())).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(query(false, maliciousIds, Map.of())).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(242));
    mvc.perform(query(true, List.of(), Map.of())).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(query(false, List.of(), Map.of())).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(242));
  }

  @Test
  void draftBudgetDoesNotExpandPublicInFilterBudgetAndErrorsRemainSafeThroughRepositoryProxy() throws Exception {
    var largeDraft = java.util.stream.IntStream.range(0, 2000).mapToObj(i -> "id-" + i).toList();
    mvc.perform(query(true, largeDraft, Map.of())).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    var tooLarge = new ArrayList<>(largeDraft); tooLarge.add("overflow");
    mvc.perform(query(true, tooLarge, Map.of())).andExpect(status().isBadRequest());
    mvc.perform(query(true, List.of(" "), Map.of())).andExpect(status().isBadRequest());
    mvc.perform(query(false, selectedIds, Map.of("id", "in:" + String.join(",", largeDraft.subList(0, 101)))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    for (String field : List.of("tenantId", "deletedAt", "unknown"))
      mvc.perform(query(false, selectedIds, Map.of(field, "equals:outside"))).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    mvc.perform(query(false, selectedIds, Map.of()).param("size", "100000")).andExpect(status().isBadRequest());
  }

  @Test
  void hiddenAndMaskedFieldsAreNotQueryOrOutputSideChannels() throws Exception {
    authorize(Map.of(Organization.class.getName() + "#description", "HIDDEN", Organization.class.getName() + "#name", "MASKED"), "tenant-a");
    mvc.perform(query(false, selectedIds, Map.of())).andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].description").doesNotExist()).andExpect(jsonPath("$.content[0].name").value("***"));
    mvc.perform(query(false, selectedIds, Map.of("description", "like:239"))).andExpect(status().isBadRequest());
    mvc.perform(query(false, selectedIds, Map.of()).param("sort", "name,asc")).andExpect(status().isBadRequest());
    mvc.perform(get("/platform/organizations/selection/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema.properties.description").doesNotExist())
        .andExpect(jsonPath("$.schema['x-query'].fields.name").doesNotExist());
    authorize(Map.of(Organization.class.getName() + "#id", "MASKED"), "tenant-a");
    var error = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(query(false, selectedIds, Map.of())));
    assertInstanceOf(AccessDeniedException.class, error.getCause());
  }

  @Test
  void missingTenantCannotQuerySchemaOrEitherSide() {
    authorize(Map.of(), null);
    for (boolean side : List.of(false, true)) {
      var error = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(query(side, selectedIds, Map.of())));
      assertInstanceOf(AccessDeniedException.class, error.getCause());
    }
    var error = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(
        get("/platform/organizations/selection/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
    assertInstanceOf(AccessDeniedException.class, error.getCause());
  }

  @Test
  void actualSchemaIsExportedForBrowserContractTests() throws Exception {
    var response = mvc.perform(get("/platform/organizations/selection/schema")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk()).andReturn();
    try (var fixture = getClass().getResourceAsStream("/query-contracts/organization-selection-schema.fixture.json")) {
      assertNotNull(fixture);
      assertEquals(mapper.readTree(fixture), mapper.readTree(response.getResponse().getContentAsString(
          java.nio.charset.StandardCharsets.UTF_8)), "Browser query contract must match the real HTTP response");
    }
    var output = java.nio.file.Path.of("build/generated-test-resources/organization-selection-schema.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }
}
