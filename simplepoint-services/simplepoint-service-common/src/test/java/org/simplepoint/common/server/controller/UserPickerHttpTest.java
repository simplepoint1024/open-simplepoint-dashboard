package org.simplepoint.common.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.base.repository.JpaUsersRepository;
import org.simplepoint.plugin.rbac.core.rest.controller.UsersController;
import org.simplepoint.plugin.rbac.core.service.impl.UserPickerQuery;
import org.simplepoint.plugin.rbac.core.service.impl.UserRoleCandidateQuery;
import org.simplepoint.plugin.rbac.core.service.impl.UsersServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual picker HTTP and SQL contracts; account capability policy is separately tested through Spring method security. */
class UserPickerHttpTest {
  SessionFactory factory;
  Session em;
  UserPickerQuery query;
  MockMvc mvc;
  List<User> users = new ArrayList<>();
  List<String> sql = new ArrayList<>();
  User deleted;
  User disabled;
  User literal;
  AuthorizationContext context;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:user-picker-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.hbm2ddl.halt_on_error", true)
        .applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
        .applySetting("hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) statement -> {sql.add(statement); return statement;})
        .build()).addAnnotatedClass(User.class).addAnnotatedClass(Role.class).addAnnotatedClass(UserRoleRelevance.class)
        .addAnnotatedClass(RoleResourceGrant.class).buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    for (int i = 0; i < 521; i++) users.add(user("Candidate" + String.format("%03d", i), "picker" + i + "@example.test", "138" + String.format("%08d", i), true));
    deleted = user("Deleted", "picker-deleted@example.test", "13899999998", true); deleted.setDeletedAt(Instant.now());
    disabled = user("Disabled", "picker-disabled@example.test", "13899999999", false);
    literal = user("Literal", "p%_!literal@example.test", "13900000000", true);
    em.flush(); em.clear(); sql.clear(); factory.getStatistics().clear();
    authorize(Map.of());
    query = new UserPickerQuery(em);
    var service = new UsersServiceImpl(null, mock(UserRepository.class), mock(DetailsProviderService.class),
        mock(UserRoleRelevanceRepository.class), mock(TenantRepository.class), mock(TenantUserRelevanceRepository.class),
        mock(RoleRepository.class), mock(ResourceAuthorizationVersionService.class), mock(ResourceGrantLogRemoteService.class),
        mock(ObjectProvider.class), mock(UserRoleCandidateQuery.class), query);
    mvc = MockMvcBuilders.standaloneSetup(new UsersController(service))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }

  User user(String name, String email, String phone, boolean enabled) {
    var user = new User(); user.setNickname(name); user.setName(name); user.setEmail(email); user.setPhoneNumber(phone);
    user.setPassword("isolated-never-select-credential"); user.setEnabled(enabled); em.persist(user); return user;
  }

  void authorize(Map<String, String> fields) {
    context = new AuthorizationContext(); context.setUserId("actor"); context.setIsAdministrator(false);
    context.setResources(Set.of("tenants.edit")); context.setFieldPermissions(fields);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
  }

  @Test
  void countedPagesBeyondFiveHundredUseOnlyLightweightProjectionAndPreserveGlobalAccountSelection() throws Exception {
    mvc.perform(get("/users/picker/items").param("keyword", "PiCkEr").param("page", "26").param("size", "20")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(521))
        .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].name").value("Candidate520"));
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    assertThat(sql).hasSize(2);
    assertThat(sql.getFirst().toLowerCase()).contains("offset", "fetch first").doesNotContain("password", "two_factor", "secret");
    mvc.perform(get("/users/picker/items").param("keyword", "picker").param("size", "500")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.pageable.pageSize").value(50))
        .andExpect(jsonPath("$.content.length()").value(50));
  }

  @Test
  void literalWildcardPrefixesCannotTurnThePickerIntoABroadSearch() throws Exception {
    mvc.perform(get("/users/picker/items").param("keyword", "p%_")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(literal.getId()));
    mvc.perform(get("/users/picker/items").param("keyword", "%%%")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    sql.clear();
    mvc.perform(get("/users/picker/items").param("keyword", "ab")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    assertThat(sql).isEmpty();
  }

  @Test
  void selectedValuesKeepRequestedOrderAndDisabledHistoryButNeverDeletedAccounts() throws Exception {
    String ids = String.join(",", users.get(520).getId(), disabled.getId(), deleted.getId(), "missing", users.getFirst().getId());
    mvc.perform(get("/users/picker/selected").param("ids", ids).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(users.get(520).getId())).andExpect(jsonPath("$[1].id").value(disabled.getId()))
        .andExpect(jsonPath("$[2].id").value(users.getFirst().getId()));
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    assertThat(sql.getFirst().toLowerCase()).doesNotContain("password", "two_factor", "secret");
  }

  @Test
  void fullHundredIdHydrationUsesOneScalarQueryAndPreservesEveryCsvEntry() throws Exception {
    String ids = users.subList(0, 100).stream().map(User::getId).collect(java.util.stream.Collectors.joining(","));
    mvc.perform(get("/users/picker/selected").param("ids", ids).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(100))
        .andExpect(jsonPath("$[0].id").value(users.getFirst().getId()))
        .andExpect(jsonPath("$[99].id").value(users.get(99).getId()));
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    assertThat(sql).hasSize(1);
    assertThat(sql.getFirst().toLowerCase()).doesNotContain("password", "two_factor", "secret");
  }

  @Test
  void unreadableFallbackFieldsAreNotSelectedSearchedOrSortedButReadableNamesStillWork() {
    authorize(Map.of(User.class.getName() + "#nickname", "HIDDEN", User.class.getName() + "#email", "MASKED",
        User.class.getName() + "#picture", "HIDDEN"));
    var result = query.search("138", PageRequest.of(0, 20));
    assertThat(result.getTotalElements()).isEqualTo(521);
    assertThat(result.getContent().getFirst().name()).isEqualTo("Candidate000");
    assertThat(result.getContent().getFirst().email()).isEqualTo("***");
    assertThat(result.getContent().getFirst().picture()).isNull();
    assertThat(sql.stream().map(String::toLowerCase)).allSatisfy(statement ->
        assertThat(statement).doesNotContain("nickname", "email", "picture", "password"));
    assertThat(query.search("picker", PageRequest.of(0, 20))).isEmpty();
    assertThat(query.selected(List.of(users.get(520).getId())).getFirst().name()).isEqualTo("Candidate520");
  }

  @Test
  void absentSearchFieldsAndProtectedIdentityFailClosedBeforeDatabaseQueries() {
    authorize(Map.of(User.class.getName() + "#email", "HIDDEN", User.class.getName() + "#phoneNumber", "MASKED"));
    assertThatThrownBy(() -> query.search("picker", PageRequest.of(0, 20))).isInstanceOf(AccessDeniedException.class);
    assertThat(sql).isEmpty();
    assertThat(query.selected(List.of(users.getFirst().getId())).getFirst().name()).isEqualTo("Candidate000");
    authorize(Map.of(User.class.getName() + "#id", "MASKED")); sql.clear();
    assertThatThrownBy(() -> query.selected(List.of(users.getFirst().getId()))).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> query.search("138", PageRequest.of(0, 20))).isInstanceOf(AccessDeniedException.class);
    assertThat(sql).isEmpty();
  }

  @Test
  void malformedPagingSortAndHydrationDoNotBecomeUnboundedDatabaseReads() {
    assertThatThrownBy(() -> query.search("picker", PageRequest.of(0, 51))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> query.search("picker", PageRequest.of(Integer.MAX_VALUE, 20))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> query.search("picker", PageRequest.of(0, 20, Sort.by("password")))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> query.search("p".repeat(321), PageRequest.of(0, 20))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> query.selected(java.util.stream.IntStream.range(0, 101).mapToObj(i -> "u" + i).toList())).isInstanceOf(IllegalArgumentException.class);
    assertThat(sql).isEmpty();
  }

  @Test
  void malformedHttpRequestsReturnBadRequestBeforeAnySqlRatherThanInternalErrors() throws Exception {
    mvc.perform(get("/users/picker/items").param("keyword", "p".repeat(321))
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isBadRequest());
    mvc.perform(get("/users/picker/items").param("keyword", "picker").param("page", String.valueOf(Integer.MAX_VALUE))
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isBadRequest());
    mvc.perform(get("/users/picker/selected").param("ids", users.subList(0, 101).stream().map(User::getId)
            .collect(java.util.stream.Collectors.joining(",")))
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isBadRequest());
    assertThat(sql).isEmpty();
  }

  @Test
  void actualMultiplePickerSchemaRetainsTheExistingRemoteProtocol() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var config = new org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration(mapper);
    var generator = config.defaultJsonSchemaGenerator(config.schemaGenerator(Set.of(config.openApiModule(),
        config.jacksonModule(), config.javaxValidationModule())));
    var schema = generator.generateSchema(org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantUsersRelevanceDto.class);
    assertThat(schema.at("/properties/userIds/x-ui/widget").asText()).isEqualTo("UserPicker");
    assertThat(schema.at("/properties/userIds/x-ui/options/selectionMode").asText()).isEqualTo("multiple");
    assertThat(schema.at("/properties/userIds/x-ui/options/endpoint").asText()).isEqualTo("/common/users/picker/items");
    assertThat(schema.at("/properties/userIds/x-ui/options/resolveEndpoint").asText()).isEqualTo("/common/users/picker/selected");
    org.junit.jupiter.api.Assertions.assertEquals(schema,
        mapper.readTree(java.nio.file.Path.of("../../simplepoint-react/scripts/tenant-user-picker-schema.fixture.json").toFile()),
        "Browser multi-select field must match the actual DTO generator");
    var output = java.nio.file.Path.of("build/generated-test-resources/tenant-user-picker-schema.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, mapper.writeValueAsString(schema));
  }

  @Test
  void legacyRepositoryProjectionAlsoExcludesDeletedRowsAndTreatsPrefixesLiterally() {
    var repositories = new JpaRepositoryFactory(em);
    repositories.setRepositoryBaseClass(org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl.class);
    var repository = repositories.getRepository(JpaUsersRepository.class);
    sql.clear(); factory.getStatistics().clear();
    assertThat(repository.searchPickerItems("p%_", PageRequest.of(0, 20)).getContent()).extracting(row -> row.id()).containsExactly(literal.getId());
    assertThat(repository.findPickerItemsByIds(List.of(deleted.getId(), disabled.getId()))).extracting(row -> row.id()).containsExactly(disabled.getId());
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
  }
}
