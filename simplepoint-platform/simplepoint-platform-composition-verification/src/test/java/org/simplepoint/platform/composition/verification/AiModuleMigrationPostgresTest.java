package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleDescriptor;
import org.simplepoint.platform.composition.ModulePersistence;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeRetrievalMode;
import org.simplepoint.plugin.ai.knowledge.api.properties.AiKnowledgeProperties;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeChunkRepository;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeChunkRecord;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeSearchSpec;
import org.simplepoint.plugin.ai.knowledge.repository.PgVectorKnowledgeChunkRepository;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Installs all AI modules and exercises native AI storage on real PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class AiModuleMigrationPostgresTest {
  @Test
  void allModuleAndSharedSchemasInstallTogetherWithoutOwnershipCollisions() {
    // This checks module-owned and shared event DDL. Flowable and complete application startup are
    // separate lifecycle contributions and must be verified before a host is declared ready.
    try (var database = new MigrationPostgresDatabase()) {
      var catalog = ModuleCatalog.discover(getClass().getClassLoader());
      var modules = catalog.plan(catalog.modules().stream().map(ModuleDescriptor::id).toList());
      var binding =
          new ModuleStorageBindings.Binding("default", "dataSource", "emf", "em", "tx", "jdbc");
      Map<String, ModuleStorageBindings.Binding> bindings = new LinkedHashMap<>();
      modules.modules().forEach(module -> bindings.put(module.id(), binding));
      var plan = ModuleMigrationPlan.discover(modules, new ModuleStorageBindings(bindings));
      var sources = Map.of("default", (DataSource) database.source());
      var engine = new ModuleMigrationEngine();
      assertTrue(plan.undeclaredModules().isEmpty());
      assertEquals(21, engine.migrate(plan, sources).size());
      var expected = new TreeSet<String>();
      plan.schemas().forEach(schema -> expected.addAll(schema.tables()));
      assertEquals(179, expected.size());
      assertEquals(
          expected,
          new TreeSet<>(
              database
                  .jdbc()
                  .queryForList(
                      "select tablename from pg_tables where schemaname=current_schema() and"
                          + " tablename not like 'sp_migration_%'",
                      String.class)));
      assertTrue(
          engine.migrate(plan, sources).stream().allMatch(outcome -> outcome.applied() == 0));
      engine.verify(plan, sources);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aiModulesInstallAndQueriesWorkAcrossDeclaredStorageGroups(boolean split) {
    List<MigrationPostgresDatabase> databases = new ArrayList<>();
    try (var context = new AnnotationConfigApplicationContext()) {
      var catalog = ModuleCatalog.discover(getClass().getClassLoader());
      var modules = catalog.plan(List.of("ai-catalog", "ai-knowledge"));
      Map<String, Object> assignments = new LinkedHashMap<>();
      if (split) {
        for (var module : modules.modules()) {
          var storage = module.configuration().getDeclaredAnnotation(ModulePersistence.class);
          if (storage == null || storage.sameStoreAs().length == 0) {
            assignments.put("simplepoint.persistence.modules." + module.id(), module.id());
            assignments.put(
                "simplepoint.persistence.sources." + module.id(), module.id() + "Source");
          }
        }
      }
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("stores", assignments));
      var persistence =
          ModulePersistencePlan.discover(
              modules, context.getEnvironment(), new DefaultResourceLoader());
      Map<String, DataSource> sources = new LinkedHashMap<>();
      for (var store : persistence.stores().values()) {
        var database = new MigrationPostgresDatabase();
        databases.add(database);
        sources.put(store.binding().store(), database.source());
        context.registerBean(store.binding().dataSource(), DataSource.class, database::source);
      }
      var plan = ModuleMigrationPlan.discover(modules, persistence.bindings());
      assertEquals(List.of(), plan.undeclaredModules());
      assertEquals(5, plan.schemas().size());
      assertEquals(26, plan.schemas().stream().mapToInt(schema -> schema.tables().size()).sum());
      assertEquals(
          persistence.bindings().require("ai-catalog"), persistence.bindings().require("ai-mcp"));
      assertEquals(
          persistence.bindings().require("ai-mcp"), persistence.bindings().require("ai-runtime"));
      assertEquals(
          persistence.bindings().require("ai-core"),
          persistence.bindings().require("ai-knowledge"));
      if (split) {
        assertNotEquals(
            persistence.bindings().require("ai-core"),
            persistence.bindings().require("ai-runtime"));
      }
      var engine = new ModuleMigrationEngine();
      assertEquals(5, engine.migrate(plan, sources).size());
      context.registerBean(ModulePlan.class, () -> modules);
      context.registerBean(AiKnowledgeProperties.class, AiKnowledgeProperties::new);
      context.register(Persistence.class, PgVectorKnowledgeChunkRepository.class);
      context.refresh();
      int entities = 0;
      for (var store : persistence.stores().values()) {
        var em = context.getBean(store.binding().entityManager(), EntityManager.class);
        entities += em.getMetamodel().getEntities().size();
        for (var entity : em.getMetamodel().getEntities()) {
          assertEquals(
              0L,
              em.createQuery("select count(e) from " + entity.getName() + " e", Long.class)
                  .getSingleResult());
        }
        var expected = new TreeSet<String>();
        plan.schemas().stream()
            .filter(schema -> schema.store().equals(store.binding().store()))
            .forEach(schema -> expected.addAll(schema.tables()));
        assertEquals(
            expected,
            new TreeSet<>(
                new JdbcTemplate(sources.get(store.binding().store()))
                    .queryForList(
                        "select tablename from pg_tables where schemaname=current_schema() and"
                            + " tablename not like 'sp_migration_%'",
                        String.class)));
      }
      assertEquals(25, entities);
      var core = new JdbcTemplate(sources.get(persistence.bindings().require("ai-core").store()));
      var runtime =
          new JdbcTemplate(sources.get(persistence.bindings().require("ai-runtime").store()));
      verifyProviderUniqueness(core);
      verifyLeasesAndTasks(runtime);
      var chunks = context.getBean(AiKnowledgeChunkRepository.class);
      var tx =
          new TransactionTemplate(
              context.getBean(
                  persistence.bindings().require("ai-knowledge").transactionManager(),
                  PlatformTransactionManager.class));
      verifyRetrieval(core, chunks, tx);
      assertTrue(
          engine.migrate(plan, sources).stream().allMatch(outcome -> outcome.applied() == 0));
      engine.verify(plan, sources);
      assertEquals(
          "FENCED",
          runtime.queryForObject(
              "select status from simpoint_ai_runtime_leases where id='lease'", String.class));
      assertEquals(
          1L, core.queryForObject("select count(*) from simpoint_ai_knowledge_chunks", Long.class));
    } finally {
      for (int i = databases.size() - 1; i >= 0; i--) {
        databases.get(i).close();
      }
    }
  }

  private static void verifyProviderUniqueness(JdbcTemplate jdbc) {
    insertProvider(jdbc, "system", "SYSTEM", null);
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insertProvider(jdbc, "duplicate", "SYSTEM", null));
    insertProvider(jdbc, "tenant-a", "TENANT", "a");
    insertProvider(jdbc, "tenant-b", "TENANT", "b");
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insertProvider(jdbc, "tenant-duplicate", "TENANT", "a"));
    jdbc.update("update simpoint_ai_providers set deleted_at=now() where id='system'");
    insertProvider(jdbc, "replacement", "SYSTEM", null);
  }

  private static void insertProvider(JdbcTemplate jdbc, String id, String scope, String tenant) {
    jdbc.update(
        "insert into simpoint_ai_providers"
            + " (id,scope_type,tenant_id,provider_type,vendor,code,name,base_url) values"
            + " (?,?,?,'OPENAI','OPENAI','same-code','Fixture','https://example.invalid')",
        id,
        scope,
        tenant);
  }

  private static void verifyLeasesAndTasks(JdbcTemplate jdbc) {
    String leaseSql =
        "insert into simpoint_ai_runtime_leases"
            + " (id,acquired_at,expires_at,renewed_at,fencing_token,lock_version,status,node_id,workload_id,node_instance_id)"
            + " values (?,now(),now()+interval '1"
            + " hour',now(),?,0,'ACTIVE','node','workload','generation')";
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(leaseSql, "invalid", 0));
    jdbc.update(leaseSql, "lease", 1);
    assertThrows(
        DataIntegrityViolationException.class, () -> jdbc.update(leaseSql, "duplicate", 2));
    jdbc.update("update simpoint_ai_runtime_leases set status='FENCED' where id='lease'");
    jdbc.update(leaseSql, "replacement", 2);
    jdbc.update(
        """
        insert into simpoint_ai_mcp_tasks
          (id,attempt_count,expires_at,lease_token,lock_version,poll_interval_millis,ttl_millis,status,
           client_hash,operation_id,subject_hash,publication_code,tool_name,client_id,subject_id,arguments_json)
        values ('task',0,now()+interval '1 hour',0,0,100,1000,'PENDING',?, 'operation',?, 'publication','tool','client','subject','{}')
        """,
        "a".repeat(64),
        "b".repeat(64));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("update simpoint_ai_mcp_tasks set status='COMPLETED'"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("update simpoint_ai_mcp_tasks set lease_owner='worker'"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("update simpoint_ai_mcp_tasks set subject_hash='invalid'"));
    jdbc.update("update simpoint_ai_mcp_tasks set status='COMPLETED',completed_at=now()");
  }

  private static void verifyRetrieval(
      JdbcTemplate jdbc, AiKnowledgeChunkRepository chunks, TransactionTemplate tx) {
    jdbc.update(
        """
        insert into simpoint_ai_knowledge_documents (id,knowledge_base_id,scope_type,source_type,status,name)
        values ('document','knowledge','SYSTEM','TEXT','READY','检索测试')
        """);
    var chunk =
        new AiKnowledgeChunkRecord(
            "chunk",
            "knowledge",
            "document",
            AiResourceScope.SYSTEM,
            null,
            0,
            "quarterly revenue report",
            "{}",
            24,
            List.of(1.0, 0.0),
            2);
    chunks.replaceDocumentChunks("document", List.of(chunk));
    for (var mode : AiKnowledgeRetrievalMode.values()) {
      var result =
          chunks.search(
              new AiKnowledgeSearchSpec(
                  "knowledge",
                  "revenue",
                  mode,
                  List.of(1.0, 0.0),
                  Set.of("document"),
                  10,
                  0.0,
                  0.7,
                  0.3));
      assertEquals(1, result.size());
      assertEquals("chunk", result.getFirst().chunkId());
    }
    assertTrue(
        chunks
            .search(
                new AiKnowledgeSearchSpec(
                    "other",
                    "revenue",
                    AiKnowledgeRetrievalMode.VECTOR,
                    List.of(1.0, 0.0),
                    Set.of(),
                    10,
                    0.0,
                    0.7,
                    0.3))
            .isEmpty());
    tx.executeWithoutResult(
        status -> {
          chunks.replaceDocumentChunks("document", List.of());
          status.setRollbackOnly();
        });
    assertEquals(
        1L, jdbc.queryForObject("select count(*) from simpoint_ai_knowledge_chunks", Long.class));
    jdbc.update("update simpoint_ai_knowledge_documents set deleted_at=now()");
    assertFalse(
        chunks
            .search(
                new AiKnowledgeSearchSpec(
                    "knowledge",
                    "revenue",
                    AiKnowledgeRetrievalMode.HYBRID,
                    List.of(1.0, 0.0),
                    Set.of(),
                    10,
                    0.0,
                    0.7,
                    0.3))
            .stream()
            .findAny()
            .isPresent());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
