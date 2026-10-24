package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Validates support-module installation, ownership, durable deduplication and restart on
 * PostgreSQL.
 */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class SupportModuleMigrationPostgresTest {
  private static final List<String> MODULES =
      List.of(
          "personal",
          "notification",
          "storage",
          "audit-logging",
          "audit-rate-limit",
          "audit-redis");

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void installsOwnedSchemasAndPreservesBusinessStateInSharedAndSeparateStores(boolean split) {
    try (var identity = new MigrationPostgresDatabase();
        var personal = new MigrationPostgresDatabase();
        var notification = new MigrationPostgresDatabase();
        var logging = new MigrationPostgresDatabase();
        var limits = new MigrationPostgresDatabase();
        var context = new AnnotationConfigApplicationContext()) {
      Map<String, MigrationPostgresDatabase> databases =
          Map.of(
              "identity",
              identity,
              "personal",
              personal,
              "notification",
              notification,
              "audit-logging",
              logging,
              "audit-rate-limit",
              limits);
      Map<String, Object> assignments = new LinkedHashMap<>();
      Map<String, DataSource> sources = new LinkedHashMap<>();
      if (split) {
        databases.forEach(
            (module, database) -> {
              assignments.put("simplepoint.persistence.modules." + module, module);
              assignments.put("simplepoint.persistence.sources." + module, module + "Source");
              sources.put(module, database.source());
              context.registerBean(module + "Source", DataSource.class, database::source);
            });
      } else {
        sources.put("default", identity.source());
        context.registerBean("dataSource", DataSource.class, identity::source);
      }
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("stores", assignments));
      var modules = ModuleCatalog.discover(getClass().getClassLoader()).plan(MODULES);
      var persistence =
          ModulePersistencePlan.discover(
              modules, context.getEnvironment(), new DefaultResourceLoader());
      var plan = ModuleMigrationPlan.discover(modules, persistence.bindings());
      assertEquals(List.of(), plan.undeclaredModules());
      assertEquals(split ? 8 : 7, plan.schemas().size());
      assertEquals(
          split ? 61 : 58,
          plan.schemas().stream().mapToInt(schema -> schema.tables().size()).sum());
      assertEquals(
          persistence.bindings().require("identity"), persistence.bindings().require("storage"));
      assertTrue(
          plan.schemas().stream().noneMatch(schema -> schema.module().equals("audit-redis")));
      var engine = new ModuleMigrationEngine();
      assertEquals(split ? 8 : 7, engine.migrate(plan, sources).size());
      context.registerBean(ModulePlan.class, () -> modules);
      context.register(Persistence.class);
      context.refresh();
      int entities = 0;
      for (var store : persistence.stores().values()) {
        var em = context.getBean(store.binding().entityManager(), EntityManager.class);
        entities += em.getMetamodel().getEntities().size();
        for (var entity : em.getMetamodel().getEntities()) {
          assertEquals(
              entity.getName().equals("PlatformSecurityState") ? 1L : 0L,
              em.createQuery("select count(e) from " + entity.getName() + " e", Long.class)
                  .getSingleResult());
        }
      }
      assertEquals(44, entities);
      var personalStore = split ? personal.jdbc() : identity.jdbc();
      var notifications = split ? notification.jdbc() : identity.jdbc();
      var rateLimits = split ? limits.jdbc() : identity.jdbc();
      final var audit = split ? logging.jdbc() : identity.jdbc();
      verifyPersonal(personalStore);
      verifyNotifications(notifications);
      verifyRateLimits(rateLimits);
      verifyAudit(
          audit,
          context.getBean(
              persistence.bindings().require("audit-logging").entityManager(),
              EntityManager.class));
      verifyStorage(
          identity.jdbc(),
          context.getBean(
              persistence.bindings().require("storage").entityManager(), EntityManager.class));
      assertTrue(engine.migrate(plan, sources).stream().allMatch(result -> result.applied() == 0));
      engine.verify(plan, sources);
      assertEquals(
          Boolean.TRUE,
          personalStore.queryForObject(
              "select archived from simpoint_personal_messages where id='message'", Boolean.class));
      assertNotNull(
          personalStore.queryForObject(
              "select read_at from simpoint_personal_messages where id='message'",
              java.time.OffsetDateTime.class));
      assertEquals(
          "REVOKED",
          notifications.queryForObject(
              "select status from simpoint_system_notifications where id='notice'", String.class));
      assertEquals(
          3L,
          notifications.queryForObject(
              "select version from simpoint_system_notifications where id='notice'", Long.class));
      assertEquals(
          1L, audit.queryForObject("select count(*) from simpoint_aud_error_logs", Long.class));
      for (var source : sources.entrySet()) {
        var expected = new TreeSet<String>();
        plan.schemas().stream()
            .filter(schema -> schema.store().equals(source.getKey()))
            .forEach(schema -> expected.addAll(schema.tables()));
        var actual =
            new JdbcTemplate(source.getValue())
                .queryForList(
                    "select tablename from pg_tables where schemaname=current_schema() and"
                        + " tablename not like 'sp_migration_%'",
                    String.class);
        assertEquals(expected, new TreeSet<>(actual));
      }
    }
  }

  private static void verifyPersonal(JdbcTemplate jdbc) {
    jdbc.update(
        """
        insert into simpoint_personal_messages
          (id,owner_id,workspace,version,title,content,priority,source,source_key,archived,read_at)
        values ('message','user','TENANT:a',2,'审批完成','明細の確認が完了しました',1,'workflow','event',true,now())
        """);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                """
                insert into simpoint_personal_messages
                  (id,owner_id,workspace,title,priority,source,source_key,archived)
                values ('duplicate','user','TENANT:a','duplicate',1,'workflow','event',false)
                """));
    jdbc.update(
        """
        insert into simpoint_personal_messages
          (id,owner_id,workspace,title,priority,source,source_key,archived)
        values ('different-owner','other','TENANT:a','other',1,'workflow','event',false)
        """);
    jdbc.update(
        """
        insert into simpoint_personal_todos
          (id,owner_id,workspace,title,status,priority,source,source_key)
        values ('todo','user','TENANT:a','Review','COMPLETED',1,'workflow','task')
        """);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                """
                insert into simpoint_personal_todos
                  (id,owner_id,workspace,title,status,priority,source,source_key)
                values ('duplicate-todo','user','TENANT:a','duplicate','PENDING',1,'workflow','task')
                """));
  }

  private static void verifyNotifications(JdbcTemplate jdbc) {
    jdbc.update(
        """
        insert into simpoint_system_notifications
          (id,title,content,audience_type,category,priority,status,source,external_key,version)
        values ('notice','平台通知','已撤销','ALL','SYSTEM','NORMAL','REVOKED','test','key',3)
        """);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                """
                insert into simpoint_system_notifications
                  (id,title,content,audience_type,category,priority,status,source,external_key)
                values ('duplicate','Notice','Body','ALL','SYSTEM','NORMAL','PUBLISHED','test','key')
                """));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("update simpoint_system_notifications set status='UNKNOWN'"));
    jdbc.update(
        "insert into simpoint_notification_receipts (id,notification_id,user_id,read_at) values"
            + " ('receipt','notice','user',now())");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into simpoint_notification_receipts (id,notification_id,user_id,read_at)"
                    + " values ('duplicate','notice','user',now())"));
  }

  private static void verifyRateLimits(JdbcTemplate jdbc) {
    jdbc.update(
        """
        insert into simpoint_aud_service_rate_limit_rules
          (id,name,service_id,key_strategy,replenish_rate,burst_capacity,requested_tokens,enabled)
        values ('rule','Service rule','app','USER_ID',10,20,1,true)
        """);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                """
                insert into simpoint_aud_service_rate_limit_rules
                  (id,name,service_id,key_strategy,replenish_rate,burst_capacity,requested_tokens,enabled)
                values ('duplicate','Duplicate','app','USER_ID',10,20,1,true)
                """));
    jdbc.update(
        """
        insert into simpoint_aud_endpoint_rate_limit_rules
          (id,name,service_id,path_pattern,key_strategy,replenish_rate,burst_capacity,requested_tokens,enabled,sort)
        values ('later','Later','app','/api/**','USER_ID',10,20,1,true,2),
               ('earlier','Earlier','app','/api/upload','USER_ID',10,20,1,true,1)
        """);
    assertEquals(
        List.of("earlier", "later"),
        jdbc.queryForList(
            "select id from simpoint_aud_endpoint_rate_limit_rules order by sort,id",
            String.class));
  }

  private static void verifyAudit(JdbcTemplate jdbc, EntityManager em) {
    String trace = "at component.method\n".repeat(700);
    jdbc.update(
        "insert into simpoint_aud_error_logs (id,occurred_at,level,message,stack_trace) values"
            + " ('error',now(),'ERROR','诊断信息',?)",
        trace);
    assertEquals(
        trace,
        em.createQuery("select e.stackTrace from ErrorLog e", String.class).getSingleResult());
    jdbc.update(
        "insert into simpoint_aud_login_logs (id,login_at,status,username) values"
            + " ('login',now(),'SUCCESS','测试用户')");
    jdbc.update(
        """
        insert into simpoint_aud_resource_grant_logs
          (id,changed_at,action,subject_type,target_type,change_type,subject_id)
        values ('grant',now(),'REVOKE','USER','RESOURCE','OFFLINE','user')
        """);
  }

  private static void verifyStorage(JdbcTemplate jdbc, EntityManager em) {
    jdbc.update(
        "insert into simpoint_saas_tenants (id,name,tenant_type,owner_id) values"
            + " ('tenant','Tenant','ORGANIZATION','owner')");
    jdbc.update(
        "insert into simpoint_storage_tenant_quotas (id,tenant_id,quota_bytes,enabled) values"
            + " ('quota','tenant',100,true)");
    jdbc.update(
        """
        insert into simpoint_storage_objects
          (id,tenant_id,provider_type,provider_code,bucket,object_key,original_file_name,content_length)
        values ('active','tenant','S3','provider','bucket','one','明細.pdf',10),
               ('deleted','tenant','S3','provider','bucket','two','old.pdf',80)
        """);
    jdbc.update("update simpoint_storage_objects set deleted_at=now() where id='deleted'");
    var summary =
        em.createQuery(
                "select q.tenantName,q.usedBytes,q.remainingBytes from StorageQuotaReadModel q",
                Object[].class)
            .getSingleResult();
    assertEquals("Tenant", summary[0]);
    assertEquals(10L, summary[1]);
    assertEquals(90L, summary[2]);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
