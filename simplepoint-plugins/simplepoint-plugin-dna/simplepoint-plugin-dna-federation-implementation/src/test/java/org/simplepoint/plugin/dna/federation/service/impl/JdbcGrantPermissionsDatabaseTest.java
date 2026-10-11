package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Set;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.data.jpa.convert.StringSetConvert;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationJdbcConnectionUser;
import org.simplepoint.plugin.dna.federation.repository.JpaFederationJdbcConnectionUserRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/** Real JPA converted-set update; the permissions action must never overwrite other grant fields. */
class JdbcGrantPermissionsDatabaseTest {
  private static SessionFactory factory;
  private Session session;
  private FederationJdbcConnectionUserServiceImpl service;
  private JpaFederationJdbcConnectionUserRepository repository;

  @BeforeAll
  static void database() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:jdbc-grant-permissions;DB_CLOSE_DELAY=-1")
        .applySetting("hibernate.connection.username", "sa")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
    factory = new MetadataSources(registry).addAnnotatedClass(FederationJdbcConnectionUser.class)
        .getMetadataBuilder().applyAttributeConverter(
            new StringSetConvert(new com.fasterxml.jackson.databind.ObjectMapper()), true)
        .build().buildSessionFactory();
  }

  @BeforeEach
  void setup() {
    session = factory.openSession();
    session.beginTransaction();
    var repositories = new JpaRepositoryFactory(session);
    repositories.setRepositoryBaseClass(BaseRepositoryImpl.class);
    repository = repositories.getRepository(JpaFederationJdbcConnectionUserRepository.class);
    service = new FederationJdbcConnectionUserServiceImpl(repository, mock(DetailsProviderService.class),
        mock(JdbcDataSourceDefinitionService.class), mock(UsersService.class), session);
    var context = new AuthorizationContext();
    context.setUserId("permission-actor");
    context.setDataScopeType("ALL");
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {
      if (session.getTransaction().isActive()) session.getTransaction().rollback();
      session.close();
    }
  }

  @AfterAll
  static void closeDatabase() {if (factory != null) factory.close();}

  private FederationJdbcConnectionUser grant() {
    var grant = new FederationJdbcConnectionUser();
    grant.setCatalogId("catalog-1");
    grant.setCatalogName("Datasource");
    grant.setCatalogCode("source");
    grant.setUserId("target-user");
    grant.setUserDisplayName("Target user");
    grant.setDescription("original description");
    grant.setOperationPermissions(Set.of("METADATA", "QUERY"));
    grant.setCreatedBy("original-owner");
    grant.setEnabled(true);
    session.persist(grant);
    session.flush();
    return grant;
  }

  @Test
  void fieldOnlyUpdatePreservesConcurrentMetadataAndUsesRealSetConversion() {
    var original = grant();
    // The action's loaded entity is deliberately stale relative to an independent metadata write.
    session.createMutationQuery("update FederationJdbcConnectionUser g set g.description = :description where g.id = :id")
        .setParameter("description", "concurrent metadata").setParameter("id", original.getId()).executeUpdate();
    var result = service.updateOperationPermissions(original.getId(), Set.of("query", "explain"));
    assertThat(result.getOperationPermissions()).containsExactlyInAnyOrder("QUERY", "EXPLAIN");
    assertThat(original.getOperationPermissions()).containsExactlyInAnyOrder("METADATA", "QUERY");
    session.clear();
    var stored = session.find(FederationJdbcConnectionUser.class, original.getId());
    assertThat(stored.getDescription()).isEqualTo("concurrent metadata");
    assertThat(stored.getCatalogId()).isEqualTo("catalog-1");
    assertThat(stored.getUserId()).isEqualTo("target-user");
    assertThat(stored.getCreatedBy()).isEqualTo("original-owner");
    assertThat(stored.getUpdatedBy()).isEqualTo("permission-actor");
    assertThat(stored.getOperationPermissions()).containsExactlyInAnyOrder("QUERY", "EXPLAIN");
  }

  @Test
  void deletedGrantCannotBeUpdatedByTheFieldOnlyRepositoryAction() {
    var original = grant();
    original.setDeletedAt(Instant.now());
    session.flush();
    assertThat(repository.updateOperationPermissions(original.getId(), Set.of("EXPLAIN"), Instant.now(), "actor"))
        .isZero();
    assertThat(session.find(FederationJdbcConnectionUser.class, original.getId()).getOperationPermissions())
        .containsExactlyInAnyOrder("METADATA", "QUERY");
  }

  @Test
  void deniedRowAndReadonlyFieldLeaveDatabasePermissionsUnchanged() {
    var original = grant();
    var context = new AuthorizationContext();
    context.setUserId("permission-actor");
    context.setDataScopeType("SELF");
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertThatThrownBy(() -> service.updateOperationPermissions(original.getId(), Set.of("EXPLAIN")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    context = new AuthorizationContext();
    context.setUserId("permission-actor");
    context.setDataScopeType("ALL");
    context.setFieldPermissions(java.util.Map.of(
        FederationJdbcConnectionUser.class.getName() + "#operationPermissions", "READ_ONLY"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertThatThrownBy(() -> service.updateOperationPermissions(original.getId(), Set.of("EXPLAIN")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    session.flush();
    session.clear();
    assertThat(session.find(FederationJdbcConnectionUser.class, original.getId()).getOperationPermissions())
        .containsExactlyInAnyOrder("METADATA", "QUERY");
  }
}
