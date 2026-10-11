package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.repository.JpaTenantRepository;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

/** Executes the exact repository JPQL against real directory entities. */
class TenantRoleRecipientsDatabaseTest {
  private SessionFactory factory;
  private Session em;
  private String query;

  @BeforeEach
  void setup() throws Exception {
    factory =
        new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting(
                        "hibernate.connection.url",
                        "jdbc:h2:mem:role-recipients-" + UUID.randomUUID())
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .applySetting(
                        "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                    .applySetting("hibernate.hbm2ddl.halt_on_error", true)
                    .build())
            .addAnnotatedClass(User.class)
            .addAnnotatedClass(Role.class)
            .addAnnotatedClass(UserRoleRelevance.class)
            .addAnnotatedClass(Tenant.class)
            .addAnnotatedClass(TenantUserRelevance.class)
            .buildMetadata()
            .buildSessionFactory();
    em = factory.openSession();
    em.beginTransaction();
    query =
        JpaTenantRepository.class
            .getMethod(
                "resolveActiveRoleMemberIds",
                String.class,
                Collection.class,
                String.class,
                Pageable.class)
            .getAnnotation(Query.class)
            .value();
  }

  @AfterEach
  void close() {
    if (em != null) {
      if (em.getTransaction().isActive()) {
        em.getTransaction().rollback();
      }
      em.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void recipientSetIsTenantSafeActiveDeduplicatedAndKeysetPaged() {
    var owner = user("Owner");
    var member = user("Member");
    var tenant = tenant("Tenant", owner);
    var foreign = tenant("Foreign", user("Foreign owner"));
    var role = role(tenant, "approver");
    var secondRole = role(tenant, "approver");
    assign(tenant, owner, role);
    assign(tenant, member, role);
    assign(tenant, member, secondRole);
    membership(tenant, member);
    var outsider = user("Outsider");
    assign(tenant, outsider, role);
    var disabled = user("Disabled");
    disabled.setEnabled(false);
    membership(tenant, disabled);
    assign(tenant, disabled, role);
    var removedMember = user("Removed member");
    membership(tenant, removedMember).setDeletedAt(Instant.now());
    assign(tenant, removedMember, role);
    var removedAssignment = user("Removed assignment");
    membership(tenant, removedAssignment);
    assign(tenant, removedAssignment, role).setDeletedAt(Instant.now());
    var foreignAssigned = user("Foreign assigned");
    membership(tenant, foreignAssigned);
    assign(foreign, foreignAssigned, role);
    var foreignRoleUser = user("Foreign role");
    membership(tenant, foreignRoleUser);
    assign(tenant, foreignRoleUser, role(foreign, "approver"));
    var deletedRoleUser = user("Deleted role");
    membership(tenant, deletedRoleUser);
    var deletedRole = role(tenant, "approver");
    deletedRole.setDeletedAt(Instant.now());
    assign(tenant, deletedRoleUser, deletedRole);
    em.flush();

    var expected = List.of(owner.getId(), member.getId()).stream().sorted().toList();
    assertThat(page(tenant, null, 200)).containsExactlyElementsOf(expected);
    assertThat(page(tenant, null, 1)).containsExactly(expected.getFirst());
    assertThat(page(tenant, expected.getFirst(), 1)).containsExactly(expected.getLast());
    assertThat(page(tenant, expected.getLast(), 1)).isEmpty();
    tenant.setDeletedAt(Instant.now());
    em.flush();
    assertThat(page(tenant, null, 200)).isEmpty();
  }

  @Test
  void legacyNullEnabledUsesTheSameActiveSemanticsAsMembershipValidation() {
    var member = user("Legacy");
    member.setEnabled(null);
    var tenant = tenant("Tenant", user("Owner"));
    membership(tenant, member).setEnabled(null);
    assign(tenant, member, role(tenant, "approver"));
    em.flush();
    assertThat(page(tenant, null, 200)).containsExactly(member.getId());
    member.setDeletedAt(Instant.now());
    em.flush();
    assertThat(page(tenant, null, 200)).isEmpty();
  }

  private List<String> page(Tenant tenant, String after, int limit) {
    return em.createQuery(query, String.class)
        .setParameter("tenantId", tenant.getId())
        .setParameter("authorities", List.of("approver"))
        .setParameter("afterUserId", after)
        .setMaxResults(limit)
        .getResultList();
  }

  private User user(String name) {
    var user = new User();
    user.setName(name);
    user.setEmail(UUID.randomUUID() + "@example.test");
    user.setPassword("test-only");
    user.setEnabled(true);
    em.persist(user);
    return user;
  }

  private Tenant tenant(String name, User owner) {
    var tenant = new Tenant();
    tenant.setName(name);
    tenant.setOwnerId(owner.getId());
    tenant.setAuthorizationVersion(0L);
    em.persist(tenant);
    return tenant;
  }

  private Role role(Tenant tenant, String authority) {
    var role = new Role();
    role.setTenantId(tenant.getId());
    role.setRoleName("Approver");
    role.setAuthority(authority);
    em.persist(role);
    return role;
  }

  private TenantUserRelevance membership(Tenant tenant, User user) {
    var membership = new TenantUserRelevance();
    membership.setTenantId(tenant.getId());
    membership.setUserId(user.getId());
    membership.setEnabled(true);
    em.persist(membership);
    return membership;
  }

  private UserRoleRelevance assign(Tenant tenant, User user, Role role) {
    var assignment = new UserRoleRelevance();
    assignment.setTenantId(tenant.getId());
    assignment.setUserId(user.getId());
    assignment.setRoleId(role.getId());
    em.persist(assignment);
    return assignment;
  }
}
