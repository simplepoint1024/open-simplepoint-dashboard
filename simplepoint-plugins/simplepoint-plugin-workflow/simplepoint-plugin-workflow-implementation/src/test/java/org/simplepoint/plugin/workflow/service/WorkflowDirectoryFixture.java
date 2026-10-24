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
          em.unwrap(org.hibernate.Session.class)
              .createNativeQuery(
                  "create table simpoint_ac_users (id varchar(64) primary key, name varchar(128),"
                      + " nickname varchar(128), email varchar(128), family_name varchar(50),"
                      + " middle_name varchar(50), given_name varchar(50), enabled boolean,"
                      + " deleted_at timestamp)")
              .executeUpdate();
          em.unwrap(org.hibernate.Session.class)
              .createNativeQuery(
                  "create table simpoint_saas_tenants (id varchar(64) primary key,"
                      + " owner_id varchar(64), deleted_at timestamp)")
              .executeUpdate();
          em.unwrap(org.hibernate.Session.class)
              .createNativeQuery(
                  "create table simpoint_saas_tenant_user_rel (tenant_id varchar(64),"
                      + " user_id varchar(64), enabled boolean, deleted_at timestamp)")
              .executeUpdate();
          em.unwrap(org.hibernate.Session.class)
              .createNativeQuery(
                  "create table simpoint_ac_roles (id varchar(64), tenant_id varchar(64),"
                      + " authority varchar(100), role_name varchar(100), deleted_at timestamp)")
              .executeUpdate();
          em.unwrap(org.hibernate.Session.class)
              .createNativeQuery(
                  "insert into simpoint_ac_users (id,name,nickname,enabled) values"
                      + " ('actor','张三','不是实名',true), ('reviewer','李四','不是实名',true)")
              .executeUpdate();
          em.unwrap(org.hibernate.Session.class)
              .createNativeQuery(
                  "insert into simpoint_saas_tenants (id,owner_id) values ('tenant','actor')")
              .executeUpdate();
          em.unwrap(org.hibernate.Session.class)
              .createNativeQuery(
                  "insert into simpoint_saas_tenant_user_rel (tenant_id,user_id,enabled)"
                      + " values ('tenant','reviewer',true)")
              .executeUpdate();
          em.unwrap(org.hibernate.Session.class)
              .createNativeQuery(
                  "insert into simpoint_ac_roles (id,tenant_id,authority,role_name)"
                      + " values ('role-1','tenant','tenant.approver','审批人员')")
              .executeUpdate();
        });
    // Simulate the remote label response from the mutable directory fixture. Production reads
    // happen in identity; list joins remain a separately tracked G22 migration.
    final EntityManager directoryEntities = em;
    org.mockito.Mockito.when(auditIdentities.resolve(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityRequest request =
                  invocation.getArgument(0);
              java.util.Map<String, String> users = new java.util.LinkedHashMap<>();
              java.util.Map<String, String> roles = new java.util.LinkedHashMap<>();
              if (!request.userIds().isEmpty()) {
                var rows =
                    directoryEntities
                        .unwrap(org.hibernate.Session.class)
                        .createNativeQuery(
                            "select u.id, "
                                + org.simplepoint.security.identity.UserDisplayName.SQL
                                + " from simpoint_ac_users u join simpoint_saas_tenants t on"
                                + " t.id=:tenant where u.id in (:ids) and u.deleted_at is null and"
                                + " coalesce(u.enabled,true)=true and t.deleted_at is null and"
                                + " (t.owner_id=u.id or exists (select 1 from"
                                + " simpoint_saas_tenant_user_rel m where m.user_id=u.id and"
                                + " m.tenant_id=t.id and m.deleted_at is null and"
                                + " coalesce(m.enabled,true)=true))",
                            Object[].class)
                        .setParameter("tenant", request.tenantId())
                        .setParameter("ids", request.userIds())
                        .getResultList();
                for (var row : rows) {
                  if (row[1] != null) {
                    users.put((String) row[0], (String) row[1]);
                  }
                }
              }
              if (!request.roleAuthorities().isEmpty()) {
                var rows =
                    directoryEntities
                        .unwrap(org.hibernate.Session.class)
                        .createNativeQuery(
                            "select authority,role_name from simpoint_ac_roles where"
                                + " tenant_id=:tenant and authority in (:roles) and deleted_at is"
                                + " null order by id",
                            Object[].class)
                        .setParameter("tenant", request.tenantId())
                        .setParameter("roles", request.roleAuthorities())
                        .getResultList();
                for (var row : rows) {
                  if (row[1] != null) {
                    roles.merge(
                        (String) row[0],
                        (String) row[1],
                        (left, right) -> left.equals(right) ? left : left + " / " + right);
                  }
                }
              }
              return new org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityLabels(
                  request.tenantId(), null, users, java.util.Map.of(), roles);
            });
  }

  /**
   * SQL projection fixture only; real source fencing and rebuilds have separate PostgreSQL tests.
   */
  protected org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelReader labelReader() {
    return new org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelReader() {
      @Override
      public <T> T read(String tenant, java.util.function.Supplier<T> query) {
        return transaction.execute(
            status -> {
              var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
              var rows =
                  jdbc.query(
                      "select u.id,"
                          + org.simplepoint.security.identity.UserDisplayName.SQL
                          + " as name from simpoint_ac_users u join simpoint_saas_tenants t on"
                          + " t.id=? where t.deleted_at is null and u.deleted_at is null and"
                          + " coalesce(u.enabled,true)=true and (t.owner_id=u.id or exists (select"
                          + " 1 from simpoint_saas_tenant_user_rel m where m.tenant_id=t.id and"
                          + " m.user_id=u.id and m.deleted_at is null and"
                          + " coalesce(m.enabled,true)=true))",
                      (row, index) -> new Object[] {row.getString(1), row.getString(2)},
                      tenant);
              jdbc.update("delete from simpoint_workflow_member_labels where tenant_id=?", tenant);
              jdbc.update(
                  "delete from simpoint_workflow_member_label_state where tenant_id=?", tenant);
              String generation = java.util.UUID.randomUUID().toString();
              String epoch = "00000000-0000-0000-0000-000000000001";
              jdbc.update(
                  "insert into simpoint_workflow_member_label_state"
                      + "(tenant_id,active_generation,source_epoch,source_revision,tenant_active,observed_epoch,observed_revision)"
                      + " values (?,?,?,0,true,?,0)",
                  tenant,
                  generation,
                  epoch,
                  epoch);
              jdbc.batchUpdate(
                  "insert into simpoint_workflow_member_labels(tenant_id,generation,user_id,name)"
                      + " values (?,?,?,?)",
                  rows.stream()
                      .map(row -> new Object[] {tenant, generation, row[0], row[1]})
                      .toList());
              return query.get();
            });
      }
    };
  }
}
