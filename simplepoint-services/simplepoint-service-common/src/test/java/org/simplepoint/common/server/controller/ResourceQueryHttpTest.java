package org.simplepoint.common.server.controller;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceAncestorRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.resource.rest.endpoint.ResourcesController;
import org.simplepoint.plugin.rbac.resource.service.impl.ResourceServiceImpl;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises the reported URL through the real resource controller, service and SQL repository. */
class ResourceQueryHttpTest {
  private SessionFactory factory;
  private Session session;
  private MockMvc mvc;
  private AuthorizationContext context;

  @BeforeEach
  void setup() {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", "jdbc:h2:mem:resource-query")
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(Resource.class)
            .buildMetadata()
            .buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    resource("Home", 1, null);
    resource("Home tools", 2, null);
    resource("Other", 3, null);
    resource("Home nested", 4, "not-a-root");
    session.flush();
    var repository =
        mock(
            ResourceRepository.class,
            withSettings()
                .defaultAnswer(delegatesTo(new BaseRepositoryImpl<>(Resource.class, session))));
    var service =
        new ResourceServiceImpl(
            repository,
            null,
            mock(ResourceAncestorRepository.class),
            mock(MicroAppService.class),
            provider(),
            provider(),
            provider(),
            provider(),
            provider(),
            provider());
    mvc =
        MockMvcBuilders.standaloneSetup(new ResourcesController(service))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    context = new AuthorizationContext();
    context.setIsAdministrator(true);
    context.setDataScopeType("ALL");
  }

  private void resource(String name, int sort, String parentId) {
    var resource = new Resource();
    resource.setCode(name.replace(' ', '.'));
    resource.setName(name);
    resource.setType(ResourceType.PAGE);
    resource.setScopeTypes(Set.of(ResourceScopeType.PLATFORM));
    resource.setSort(sort);
    resource.setParentId(parentId);
    session.persist(resource);
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectProvider<T> provider() {
    return mock(ObjectProvider.class);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {
      if (session.getTransaction().isActive()) session.getTransaction().rollback();
      session.close();
    }
    if (factory != null) factory.close();
  }

  @Test
  void resourceAdvancedSearchWithDescendingSortReturnsMatchingRoots() throws Exception {
    mvc.perform(
            get("/resources")
                .param("page", "0")
                .param("size", "20")
                .param("sort", "sort,desc")
                .param(
                    "$query",
                    """
                    {"version":1,"where":{"op":"and","children":[
                      {"field":"name","op":"like","value":"Ho"}]}}
                    """)
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Home tools"))
        .andExpect(jsonPath("$.content[1].name").value("Home"))
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void numericSortFilterAndMultipleSortOrdersRemainIndependent() throws Exception {
    mvc.perform(
            get("/resources")
                .param("page", "0")
                .param("size", "20")
                .param("sort", "sort,desc", "name,asc")
                .param(
                    "$query",
                    """
                    {"version":1,"where":{"op":"and","children":[
                      {"field":"name","op":"like","value":"Ho"},
                      {"field":"sort","op":"than:equal:greater","value":2}]}}
                    """)
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Home tools"))
        .andExpect(jsonPath("$.content[0].sort").value(2))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void invalidResourceQueryReturnsTheSafeValidationCode() throws Exception {
    mvc.perform(
            get("/resources")
                .param(
                    "$query",
                    """
                    {"version":1,"where":{"field":"sort","op":"equals","value":"invalid"}}
                    """)
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
        .andExpect(jsonPath("$.message").value("筛选值与字段类型不匹配"));
  }
}
