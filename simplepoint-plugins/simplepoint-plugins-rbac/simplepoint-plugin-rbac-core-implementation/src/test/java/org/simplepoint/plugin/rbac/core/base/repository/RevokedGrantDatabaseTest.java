package org.simplepoint.plugin.rbac.core.base.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/** Authorization bootstrap must exclude history even before request filters are installed. */
class RevokedGrantDatabaseTest {
  @Test
  void revokedGrantsCannotSupplyAuthoritiesOrScopeTemplates() throws Exception {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:revoked-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
    try (var factory = new MetadataSources(registry).addAnnotatedClass(RoleResourceGrant.class)
        .buildMetadata().buildSessionFactory(); var session = factory.openSession()) {
      session.beginTransaction();
      var revoked = new RoleResourceGrant();
      revoked.setTenantId("tenant"); revoked.setRoleId("role");
      revoked.setResourceCode("app.runtime.page.old"); revoked.setDeletedAt(Instant.now());
      session.persist(revoked); session.flush();
      var grants = new JpaRepositoryFactory(session).getRepository(JpaRoleResourceGrantRepository.class);
      assertThat(grants.findByRoleIdIn(List.of("role"))).isEmpty();
      assertThat(grants.findFirstByTenantIdAndRoleId("tenant", "role")).isEmpty();
      assertThat(grants.findTenantIdsByResourceCodes(List.of(revoked.getResourceCode()))).isEmpty();
      var active = new RoleResourceGrant();
      active.setTenantId("tenant"); active.setRoleId("role"); active.setResourceCode("app.runtime.page.new");
      session.persist(active); session.flush();
      String authorityQuery = JpaUsersRepository.class.getMethod("loadResourcesInRoleIds", List.class)
          .getAnnotation(Query.class).value();
      assertThat(session.createQuery(authorityQuery, String.class).setParameter("roleIds", List.of("role"))
          .getResultList()).containsExactly(active.getResourceCode());
      String roleQuery = JpaRolesRepository.class.getMethod("authorized", String.class, String.class)
          .getAnnotation(Query.class).value();
      assertThat(session.createQuery(roleQuery, String.class).setParameter("tenantId", "tenant")
          .setParameter("roleId", "role").getResultList()).containsExactly(active.getResourceCode());
      assertThat(grants.findByRoleIdIn(List.of("role"))).containsExactly(active);
      assertThat(grants.findFirstByTenantIdAndRoleId("tenant", "role")).contains(active);
      grants.updateScopeForRole("tenant", "role", "new-scope", null);
      session.clear();
      assertThat(session.find(RoleResourceGrant.class, revoked.getId()).getDataScopeId()).isNull();
      assertThat(session.find(RoleResourceGrant.class, active.getId()).getDataScopeId()).isEqualTo("new-scope");
      session.getTransaction().rollback();
    }
  }
}
