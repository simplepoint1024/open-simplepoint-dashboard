package org.simplepoint.plugin.workflow.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/** Minimal scalar directory contract; native Flowable tables always come from the real engine. */
abstract class WorkflowDirectoryFixture extends WorkflowEngineFixture {
  protected EntityManager em;

  @BeforeEach
  void readDirectoryFixture() {
    em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    // Keep the mocked service directory consistent with this real read-model directory.
    org.mockito.Mockito.when(
            tenants.resolveActiveMemberIds(
                org.mockito.ArgumentMatchers.eq("tenant"),
                org.mockito.ArgumentMatchers.anyCollection()))
        .thenAnswer(
            invocation -> {
              java.util.Collection<String> requested = invocation.getArgument(1);
              return requested.stream()
                  .filter(user -> java.util.List.of("actor", "reviewer").contains(user))
                  .toList();
            });
    transaction.executeWithoutResult(
        status -> {
          em.createNativeQuery(
                  "create table simpoint_ac_users (id varchar(64) primary key, name varchar(128),"
                      + " nickname varchar(128), email varchar(128), family_name varchar(50),"
                      + " middle_name varchar(50), given_name varchar(50), enabled boolean, deleted_at timestamp)")
              .executeUpdate();
          em.createNativeQuery(
                  "create table simpoint_saas_tenants (id varchar(64) primary key,"
                      + " owner_id varchar(64), deleted_at timestamp)")
              .executeUpdate();
          em.createNativeQuery(
                  "create table simpoint_saas_tenant_user_rel (tenant_id varchar(64),"
                      + " user_id varchar(64), enabled boolean, deleted_at timestamp)")
              .executeUpdate();
          em.createNativeQuery(
                  "create table simpoint_ac_roles (id varchar(64), tenant_id varchar(64),"
                      + " authority varchar(100), role_name varchar(100), deleted_at timestamp)")
              .executeUpdate();
          em.createNativeQuery(
                  "insert into simpoint_ac_users (id,name,nickname,enabled) values"
                      + " ('actor','张三','不是实名',true), ('reviewer','李四','不是实名',true)")
              .executeUpdate();
          em.createNativeQuery(
                  "insert into simpoint_saas_tenants (id,owner_id) values ('tenant','actor')")
              .executeUpdate();
          em.createNativeQuery(
                  "insert into simpoint_saas_tenant_user_rel (tenant_id,user_id,enabled)"
                      + " values ('tenant','reviewer',true)")
              .executeUpdate();
          em.createNativeQuery(
                  "insert into simpoint_ac_roles (id,tenant_id,authority,role_name)"
                      + " values ('role-1','tenant','tenant.approver','审批人员')")
              .executeUpdate();
        });
  }
}
