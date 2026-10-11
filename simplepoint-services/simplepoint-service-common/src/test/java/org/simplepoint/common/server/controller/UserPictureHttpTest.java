package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.repository.*;
import org.simplepoint.plugin.rbac.core.base.repository.JpaUsersRepository;
import org.simplepoint.plugin.rbac.core.rest.controller.UsersController;
import org.simplepoint.plugin.rbac.core.service.impl.UserRoleCandidateQuery;
import org.simplepoint.plugin.rbac.core.service.impl.UsersServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.repository.*;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real field-only SQL and real JSON command binding, isolated from business accounts. */
class UserPictureHttpTest {
  SessionFactory factory;
  Session session;
  MockMvc mvc;
  User user, other;
  JpaUsersRepository repository;

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:picture-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.hbm2ddl.halt_on_error", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(User.class).addAnnotatedClass(Role.class)
        .addAnnotatedClass(UserRoleRelevance.class).addAnnotatedClass(RoleResourceGrant.class)
        .buildMetadata().buildSessionFactory();
    session = factory.openSession(); session.beginTransaction();
    user = seed("current"); other = seed("other"); session.flush();
    var repositories = new JpaRepositoryFactory(session);
    repositories.setRepositoryBaseClass(org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl.class);
    repository = repositories.getRepository(JpaUsersRepository.class);
    var service = new UsersServiceImpl(null, repository, mock(DetailsProviderService.class),
        mock(UserRoleRelevanceRepository.class), mock(TenantRepository.class), mock(TenantUserRelevanceRepository.class),
        mock(RoleRepository.class), mock(ResourceAuthorizationVersionService.class), mock(ResourceGrantLogRemoteService.class),
        mock(ObjectProvider.class), mock(UserRoleCandidateQuery.class), new org.simplepoint.plugin.rbac.core.service.impl.UserPickerQuery(session));
    mvc = MockMvcBuilders.standaloneSetup(new UsersController(service))
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter()).build();
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }
  User seed(String name) {
    var result = new User(); result.setName(name); result.setNickname(name); result.setEmail(name + "@example.test");
    result.setPassword("stored-password"); result.setTwoFactorEnabled(true); result.setSuperAdmin(true);
    result.setPicture("/common/object-storage/images/old"); session.persist(result); return result;
  }
  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    if (session != null) {if (session.getTransaction().isActive()) session.getTransaction().rollback(); session.close();}
    if (factory != null) factory.close();
  }

  @Test
  void pictureUpdatePreservesConcurrentProfileFieldsAndIgnoresMassAssignment() throws Exception {
    // Simulates newer persisted profile data than the browser snapshot used to upload an avatar.
    session.createMutationQuery("update User u set u.nickname = 'Newer profile', u.email = 'newer@example.test' where u.id = :id")
        .setParameter("id", user.getId()).executeUpdate(); session.clear();
    mvc.perform(put("/users/me/picture").contentType(MediaType.APPLICATION_JSON).content("""
        {"picture":"/common/object-storage/images/new", "nickname":"stale profile", "email":null,
          "id":"%s", "superAdmin":false, "twoFactorEnabled":false}
        """.formatted(other.getId()))).andExpect(status().isOk());
    session.clear(); var fresh = session.find(User.class, user.getId());
    assertEquals("Newer profile", fresh.getNickname()); assertEquals("newer@example.test", fresh.getEmail());
    assertEquals("/common/object-storage/images/new", fresh.getPicture()); assertEquals("stored-password", fresh.getPassword());
    assertTrue(fresh.getSuperAdmin()); assertTrue(fresh.getTwoFactorEnabled()); assertEquals(user.getId(), fresh.getUpdatedBy());
    assertEquals("/common/object-storage/images/old", session.find(User.class, other.getId()).getPicture());
  }
  @Test
  void pictureRemovalIsFieldOnlyAndDeletedAccountsCannotBeUpdated() throws Exception {
    mvc.perform(put("/users/me/picture").contentType(MediaType.APPLICATION_JSON).content("{\"picture\":null}"))
        .andExpect(status().isOk());
    session.clear(); assertNull(session.find(User.class, user.getId()).getPicture());
    assertEquals("current", session.find(User.class, user.getId()).getNickname());
    var gone = seed("deleted"); gone.setDeletedAt(Instant.now()); session.flush();
    assertEquals(0, repository.updateCurrentPicture(gone.getId(), "/common/object-storage/images/new", Instant.now()));
    assertEquals(0, repository.updateCurrentPicture("unknown", null, Instant.now()));
  }
  @Test
  void endpointRequiresAuthenticationAndDerivesItsTargetFromThePrincipal() throws Exception {
    var method = UsersController.class.getMethod("updateCurrentPicture",
        org.simplepoint.plugin.rbac.core.api.pojo.command.UserPictureUpdateCommand.class);
    assertEquals("isAuthenticated()", method.getAnnotation(PreAuthorize.class).value());
    var controller = new UsersController(mock(org.simplepoint.plugin.rbac.core.api.service.UsersService.class));
    SecurityContextHolder.clearContext();
    assertThrows(IllegalStateException.class, () -> controller.updateCurrentPicture(
        new org.simplepoint.plugin.rbac.core.api.pojo.command.UserPictureUpdateCommand(null)));
  }
}
