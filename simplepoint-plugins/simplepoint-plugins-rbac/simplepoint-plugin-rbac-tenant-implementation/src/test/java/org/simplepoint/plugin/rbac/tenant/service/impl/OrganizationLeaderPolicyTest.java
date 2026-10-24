package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.plugin.rbac.tenant.api.entity.*;
import org.simplepoint.plugin.rbac.tenant.api.error.OrganizationLeaderException;
import org.simplepoint.security.entity.User;

class OrganizationLeaderPolicyTest {
  SessionFactory factory;
  Session em;
  OrganizationLeaderPolicy policy;
  Tenant tenant;
  User owner, member, foreign;
  TenantUserRelevance relation;

  @BeforeEach void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:org-leader-"+UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.hbm2ddl.halt_on_error", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl").build();
    factory = new MetadataSources(registry).addAnnotatedClass(Tenant.class)
        .addAnnotatedClass(TenantUserRelevance.class).addAnnotatedClass(User.class)
        .buildMetadata().buildSessionFactory();
    em = factory.openSession();em.beginTransaction();
    owner=user("Owner"); member=user("Leader 漢字😀"); foreign=user("Foreign");
    tenant=new Tenant();tenant.setName("Tenant");tenant.setAuthorizationVersion(0L);tenant.setOwnerId(owner.getId());em.persist(tenant);
    var other=new Tenant();other.setName("Other");other.setAuthorizationVersion(0L);other.setOwnerId(foreign.getId());em.persist(other);
    relation=new TenantUserRelevance();relation.setTenantId(tenant.getId());relation.setUserId(member.getId());em.persist(relation);
    em.flush();policy=new OrganizationLeaderPolicy(em);
  }
  private User user(String name) {var user=new User();user.setName(name);user.setEnabled(true);em.persist(user);return user;}
  @AfterEach void cleanup() {if(em!=null){em.getTransaction().rollback();em.close();}if(factory!=null)factory.close();}

  @Test void activeMemberAndOwnerCanLeadWithoutChangingMembershipOrRoles() {
    policy.validate(tenant.getId(), member.getId());policy.validate(tenant.getId(), owner.getId());policy.validate(tenant.getId(), null);
    assertThat(policy.label(tenant.getId(), member.getId())).isEqualTo("Leader 漢字😀");
    assertThat(relation.getOrgId()).isNull();
    assertThatThrownBy(()->policy.validate(tenant.getId(), foreign.getId())).isInstanceOf(OrganizationLeaderException.class);
    assertThat(policy.label(tenant.getId(), foreign.getId())).isNull();
  }
  @Test void disabledDeletedAndMissingAccountsCannotBeAssignedOrNamed() {
    relation.setEnabled(false);em.flush();
    assertThatThrownBy(()->policy.validate(tenant.getId(), member.getId())).isInstanceOf(OrganizationLeaderException.class);
    assertThat(policy.label(tenant.getId(), member.getId())).isNull();
    relation.setEnabled(true);relation.setDeletedAt(Instant.now());em.flush();
    assertThatThrownBy(()->policy.validate(tenant.getId(), member.getId())).isInstanceOf(OrganizationLeaderException.class);
    relation.setDeletedAt(null);member.setEnabled(false);em.flush();
    assertThatThrownBy(()->policy.validate(tenant.getId(), member.getId())).isInstanceOf(OrganizationLeaderException.class);
    member.setEnabled(true);member.setDeletedAt(Instant.now());em.flush();
    assertThatThrownBy(()->policy.validate(tenant.getId(), member.getId())).isInstanceOf(OrganizationLeaderException.class);
    assertThatThrownBy(()->policy.validate(tenant.getId(), "missing")).isInstanceOf(OrganizationLeaderException.class);
  }
}
