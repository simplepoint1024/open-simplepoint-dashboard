package org.simplepoint.plugin.dna.runtime.service.relation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalIsolationMode;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalRelationCatalogEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalRelationDefinitionEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalRelationGuardEntity;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalReferenceResolver;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL control locks and durable dependencies, independent of any in-process mutex. */
class PhysicalRelationCatalogDatabaseTest {
  private static final String TENANT = "tenant-a";
  private String url;
  private String user;
  private String password;
  private String namespace;
  private SessionFactory factory;
  private StandardServiceRegistry registry;
  private EntityManager em;
  private TransactionTemplate tx;
  private PhysicalRelationCatalogBoundary boundary;
  private PhysicalRelationCatalog catalog;
  private final PhysicalSchemaCodec codec =
      new PhysicalSchemaCodec(new ObjectMapper().findAndRegisterModules());
  private final PhysicalSchemaDefinition empty =
      new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(), List.of(), null, true);
  private PhysicalSchemaDefinition sourceSchema;
  private PhysicalSchemaTarget sourceTarget;
  private PhysicalSchemaTarget target;

  @BeforeEach
  void setup() throws Exception {
    url = System.getenv("DNA_RUNTIME_TEST_POSTGRES_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable PostgreSQL is required");
    user = System.getenv("DNA_RUNTIME_TEST_POSTGRES_USER");
    password = System.getenv("DNA_RUNTIME_TEST_POSTGRES_PASSWORD");
    namespace = "sp_relation_catalog_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password);
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + namespace);
      statement.execute("SET search_path TO " + namespace);
      for (String file :
          List.of(
              "V1__dna_runtime_foundation.sql",
              "V2__durable_publication_plan.sql",
              "V3__relation_catalog.sql",
              "V4__physical_namespace_binding.sql")) {
        ScriptUtils.executeSqlScript(
            connection,
            new ClassPathResource(
                "META-INF/simplepoint/modules/dna-runtime/db/postgresql/" + file));
      }
    }
    registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", url)
            .applySetting("hibernate.connection.username", user)
            .applySetting("hibernate.connection.password", password)
            .applySetting("hibernate.default_schema", namespace)
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .applySetting(
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
            .build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(PhysicalModelEntity.class)
            .addAnnotatedClass(PhysicalModelVersionEntity.class)
            .addAnnotatedClass(PhysicalModelBindingEntity.class)
            .addAnnotatedClass(SchemaMigrationEntity.class)
            .addAnnotatedClass(PhysicalRelationCatalogEntity.class)
            .addAnnotatedClass(PhysicalRelationDefinitionEntity.class)
            .addAnnotatedClass(PhysicalRelationGuardEntity.class)
            .buildMetadata()
            .buildSessionFactory();
    em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    tx = new TransactionTemplate(new JpaTransactionManager(factory));
    tx.setTimeout(15);
    boundary = new PhysicalRelationCatalogBoundary(em);
    catalog = newCatalog();
    sourceSchema = sourceSchema("customer_id");
    target =
        new PhysicalSchemaTarget(
            "customer", new RuntimeMetadataTarget("store", "", "forms", "customers"));
    sourceTarget =
        new PhysicalSchemaTarget(
            "trade",
            new RuntimeMetadataTarget("store", "", "forms", "trades"),
            Map.of("customer", target.metadataTarget()));
    tx.executeWithoutResult(
        status -> {
          boundary.initialize(TENANT);
          boundary.initialize("tenant-b");
          seedModel("customer", TENANT, empty, target, true);
          seedModel("trade", TENANT, sourceSchema, sourceTarget, false);
          seedModel(
              "other",
              "tenant-b",
              empty,
              new PhysicalSchemaTarget(
                  "other", new RuntimeMetadataTarget("store", "", "forms", "other")),
              true);
          em.persist(migration("migration", "trade", null, 1));
        });
  }

  @AfterEach
  void cleanup() throws Exception {
    if (factory != null) {
      factory.close();
    }
    if (registry != null) {
      StandardServiceRegistryBuilder.destroy(registry);
    }
    if (namespace != null) {
      try (var connection = DriverManager.getConnection(url, user, password);
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA " + namespace + " CASCADE");
      }
    }
  }

  @Test
  void acquiredLocksSurviveFlushButNeverLeakToSuspendedOrSubsequentTransactions() {
    tx.executeWithoutResult(
        status -> {
          boundary.write(TENANT);
          em.flush();
          boundary.requireHeld(TENANT, true);
          new PhysicalRelationCatalogBoundary(em).requireHeld(TENANT, true);
          var independent = new TransactionTemplate(tx.getTransactionManager());
          independent.setPropagationBehavior(
              org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
          independent.executeWithoutResult(
              inner ->
                  assertThatThrownBy(() -> boundary.requireHeld(TENANT, false))
                      .isInstanceOf(IllegalStateException.class));
          boundary.requireHeld(TENANT, true);
        });
    tx.executeWithoutResult(
        status ->
            assertThatThrownBy(() -> boundary.requireHeld(TENANT, false))
                .isInstanceOf(IllegalStateException.class));
    tx.executeWithoutResult(
        status -> {
          boundary.read(TENANT);
          em.flush();
          assertThatThrownBy(() -> boundary.write(TENANT))
              .isInstanceOf(IllegalStateException.class);
        });
  }

  @Test
  void retainedSourceVersionsProtectTargetFieldsAfterRenameUpgradeAndOffline() {
    declareNameDependency();
    prepare();
    tx.executeWithoutResult(status -> activate("migration", sourceSchema, sourceTarget));
    var renamed = namedTarget("renamed_label", LogicalFieldType.STRING);
    tx.executeWithoutResult(
        status -> {
          catalog.write(TENANT);
          catalog.requireCompatibleTarget(TENANT, "customer", renamed);
          // A new source version no longer displays this field; an older published page still can.
          var next = sourceSchema("customer_id");
          seedVersion("trade", TENANT, 2, next);
          em.find(PhysicalModelEntity.class, "trade").setLatestVersion(2);
          var migration = migration("source-next", "trade", 1, 2);
          em.persist(migration);
          catalog.prepare(TENANT, "publisher", migration, next, sourceTarget);
          activate("source-next", next, sourceTarget);
          em.find(PhysicalModelEntity.class, "trade").setStatus(PhysicalModelStatus.RETIRED);
        });
    catalog = newCatalog();
    for (var incompatible : List.of(empty, namedTarget("name", LogicalFieldType.BOOLEAN))) {
      rejected(
          () ->
              tx.executeWithoutResult(
                  status -> {
                    catalog.write(TENANT);
                    catalog.requireCompatibleTarget(TENANT, "customer", incompatible);
                  }),
          DnaRuntimeErrorCode.SCHEMA_INVALID);
    }
    tx.executeWithoutResult(
        status -> {
          catalog.write("tenant-b");
          catalog.requireCompatibleTarget("tenant-b", "other", empty);
        });
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> catalog.requireCompatibleTarget(TENANT, "customer", renamed)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void targetPublicationRechecksDependenciesBeforeInstallingGuardsOrExecutingDdl() {
    declareNameDependency();
    // This draft predates the source's publication and cannot rely on its earlier validation.
    tx.executeWithoutResult(
        status -> {
          seedVersion("customer", TENANT, 2, empty);
          em.find(PhysicalModelEntity.class, "customer").setLatestVersion(2);
          em.persist(migration("target-next", "customer", 1, 2));
        });
    prepare();
    tx.executeWithoutResult(status -> activate("migration", sourceSchema, sourceTarget));
    rejected(
        () ->
            tx.executeWithoutResult(
                status -> {
                  catalog.write(TENANT);
                  catalog.prepare(
                      TENANT,
                      "publisher",
                      em.find(SchemaMigrationEntity.class, "target-next"),
                      empty,
                      target);
                }),
        DnaRuntimeErrorCode.SCHEMA_INVALID);
    assertThat(count("PhysicalRelationGuardEntity")).isEqualTo(2);
    assertThat(count("PhysicalRelationDefinitionEntity")).isEqualTo(1);
  }

  private void declareNameDependency() {
    var reference = sourceSchema.references().getFirst();
    sourceSchema =
        new PhysicalSchemaDefinition(
            sourceSchema.contractVersion(),
            sourceSchema.fields(),
            List.of(),
            null,
            true,
            List.of(
                new LogicalReferenceDefinition(
                    reference.relationId(),
                    reference.fieldId(),
                    reference.targetModelId(),
                    false,
                    List.of(new LogicalReferenceField("name", LogicalFieldType.STRING)))));
    tx.executeWithoutResult(
        status -> {
          replaceFixtureSchema("customer", namedTarget("name", LogicalFieldType.STRING));
          replaceFixtureSchema("trade", sourceSchema);
        });
  }

  private PhysicalSchemaDefinition namedTarget(String column, LogicalFieldType type) {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(
            new LogicalFieldDefinition(
                "name",
                column,
                "Name",
                type,
                true,
                type == LogicalFieldType.STRING ? 200 : null,
                null,
                null,
                null)),
        List.of(),
        null,
        true);
  }

  private void replaceFixtureSchema(String id, PhysicalSchemaDefinition schema) {
    var encoded = codec.encode(schema);
    // Test-fixture installation only: the production mapping correctly makes these columns
    // immutable.
    em.createNativeQuery(
            "UPDATE {h-schema}simpoint_dna_physical_model_versions SET schema_json=:json,"
                + " schema_checksum=:checksum WHERE tenant_id=:tenant AND physical_model_id=:model"
                + " AND version_number=1")
        .setParameter("json", encoded.json())
        .setParameter("checksum", encoded.checksum())
        .setParameter("tenant", TENANT)
        .setParameter("model", id)
        .executeUpdate();
  }

  @Test
  void guardsSurviveFailureAndRestartUntilActivationCommits() {
    prepare();
    prepare(); // Same migration and exact snapshot are idempotent.
    assertThat(count("PhysicalRelationGuardEntity")).isEqualTo(2);
    assertThat(count("PhysicalRelationDefinitionEntity")).isEqualTo(1);
    tx.executeWithoutResult(
        status ->
            em.find(SchemaMigrationEntity.class, "migration")
                .setStatus(SchemaMigrationStatus.FAILED));
    catalog = newCatalog(); // No in-memory state is needed after restart.
    rejected(
        () ->
            tx.executeWithoutResult(status -> catalog.admit(TENANT, List.of(scope(empty, target)))),
        DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
    rejected(
        () ->
            tx.executeWithoutResult(
                status -> {
                  catalog.write(TENANT);
                  catalog.requireMutable(TENANT, "customer", null);
                }),
        DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
    rejected(
        () ->
            tx.executeWithoutResult(
                status -> {
                  catalog.write(TENANT);
                  catalog.activate(
                      TENANT,
                      "publisher",
                      em.find(SchemaMigrationEntity.class, "migration"),
                      sourceSchema,
                      sourceTarget);
                }),
        DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED);
    // A rolled back activation must not clear durable guards.
    tx.executeWithoutResult(
        status -> {
          activate("migration", sourceSchema, sourceTarget);
          status.setRollbackOnly();
        });
    rejected(
        () -> tx.executeWithoutResult(status -> catalog.admitModels(TENANT, List.of("customer"))),
        DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
    tx.executeWithoutResult(status -> activate("migration", sourceSchema, sourceTarget));
    tx.executeWithoutResult(
        status ->
            catalog.admit(
                TENANT, List.of(scope(sourceSchema, sourceTarget), scope(empty, target))));
    assertThat(count("PhysicalRelationGuardEntity"))
        .isEqualTo(2); // Retain historical participants.
    assertThat(
            tx.<Long>execute(
                status ->
                    em.createQuery(
                            "select count(g) from PhysicalRelationGuardEntity g where"
                                + " g.released=false",
                            Long.class)
                        .getSingleResult()))
        .isZero();
    rejected(
        () ->
            tx.executeWithoutResult(
                status -> catalog.admit("tenant-b", List.of(scope(empty, target)))),
        DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_ACTIVE);
  }

  @Test
  void directoryVersionsRetainOldMappingsAndRejectStaleOrMissingExecutionMetadata() {
    prepare();
    tx.executeWithoutResult(status -> activate("migration", sourceSchema, sourceTarget));
    var renamed = sourceSchema("buyer_id");
    tx.executeWithoutResult(
        status -> {
          catalog.write(TENANT);
          catalog.requireMutable(TENANT, "trade", null);
          var source = em.find(PhysicalModelEntity.class, "trade");
          source.setLatestVersion(2);
          source.setStatus(PhysicalModelStatus.VALIDATING);
          seedVersion("trade", TENANT, 2, renamed);
          em.persist(migration("rename", "trade", 1, 2));
          catalog.prepare(
              TENANT,
              "publisher",
              em.find(SchemaMigrationEntity.class, "rename"),
              renamed,
              sourceTarget);
        });
    assertThat(count("PhysicalRelationDefinitionEntity")).isEqualTo(2);
    rejected(
        () ->
            tx.executeWithoutResult(status -> catalog.admit(TENANT, List.of(scope(empty, target)))),
        DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
    tx.executeWithoutResult(status -> activate("rename", renamed, sourceTarget));
    rejected(
        () ->
            tx.executeWithoutResult(
                status -> catalog.admit(TENANT, List.of(scope(sourceSchema, sourceTarget)))),
        DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_ACTIVE);
    tx.executeWithoutResult(
        status ->
            catalog.admit(
                TENANT, List.of(new PhysicalRelationCatalog.Scope(2, renamed, sourceTarget))));
    var wrongTarget =
        new PhysicalSchemaTarget(
            "trade",
            sourceTarget.metadataTarget(),
            Map.of("customer", new RuntimeMetadataTarget("store", "", "forms", "unrelated")));
    rejected(
        () ->
            tx.executeWithoutResult(
                status ->
                    catalog.admit(
                        TENANT,
                        List.of(new PhysicalRelationCatalog.Scope(2, renamed, wrongTarget)))),
        DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED);
    tx.executeWithoutResult(
        status ->
            em.createNativeQuery(
                    "DELETE FROM {h-schema}simpoint_dna_relation_definitions WHERE"
                        + " source_version=2")
                .executeUpdate());
    rejected(
        () -> tx.executeWithoutResult(status -> catalog.admitModels(TENANT, List.of("trade"))),
        DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED);
  }

  @Test
  void overlappingMigrationsCannotStealParticipantsAndDatabasePreventsTenantOrSoftDeleteBypass() {
    prepare();
    tx.executeWithoutResult(
        status -> {
          seedModel(
              "trade-two",
              TENANT,
              sourceSchema,
              new PhysicalSchemaTarget(
                  "trade-two", new RuntimeMetadataTarget("store", "", "forms", "trade_two")),
              false);
          em.persist(migration("overlap", "trade-two", null, 1));
        });
    var overlapping =
        new PhysicalSchemaTarget(
            "trade-two",
            new RuntimeMetadataTarget("store", "", "forms", "trade_two"),
            sourceTarget.referenceTargets());
    rejected(
        () ->
            tx.executeWithoutResult(
                status -> {
                  catalog.write(TENANT);
                  catalog.prepare(
                      TENANT,
                      "publisher",
                      em.find(SchemaMigrationEntity.class, "overlap"),
                      sourceSchema,
                      overlapping);
                }),
        DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        em.createNativeQuery(
                                "UPDATE {h-schema}simpoint_dna_relation_guards SET"
                                    + " deleted_at=CURRENT_TIMESTAMP")
                            .executeUpdate()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        em.createNativeQuery(
                                "UPDATE {h-schema}simpoint_dna_relation_definitions SET"
                                    + " target_model_id='other'")
                            .executeUpdate()))
        .isInstanceOf(RuntimeException.class);
    rejected(
        () ->
            tx.executeWithoutResult(status -> catalog.admit(TENANT, List.of(scope(empty, target)))),
        DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
    assertThat(count("PhysicalRelationDefinitionEntity")).isEqualTo(1);
  }

  @Test
  void detailPublicationProtectsItsAggregateRootAndIndependentReferenceTarget() {
    var parent =
        new PhysicalSchemaTarget(
            "order", new RuntimeMetadataTarget("store", "", "forms", "orders"));
    var detail =
        new PhysicalSchemaTarget(
            "line",
            new RuntimeMetadataTarget("store", "", "forms", "lines"),
            sourceTarget.referenceTargets());
    var fields = new java.util.ArrayList<>(sourceSchema.fields());
    fields.add(
        new LogicalFieldDefinition(
            "parent", "order_id", "订单", LogicalFieldType.UUID, false, null, null, null, null));
    var detailSchema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            fields,
            List.of(),
            new OwnedModelRelation("order", "parent"),
            null,
            sourceSchema.references());
    tx.executeWithoutResult(
        status -> {
          catalog.write(TENANT);
          seedModel("order", TENANT, empty, parent, true);
          seedModel("line", TENANT, detailSchema, detail, false);
          em.persist(migration("detail", "line", null, 1));
          catalog.prepare(
              TENANT,
              "publisher",
              em.find(SchemaMigrationEntity.class, "detail"),
              detailSchema,
              detail);
        });
    rejected(
        () -> tx.executeWithoutResult(status -> catalog.admitModels(TENANT, List.of("order"))),
        DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
    rejected(
        () -> tx.executeWithoutResult(status -> catalog.admitModels(TENANT, List.of("customer"))),
        DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
    tx.executeWithoutResult(status -> activate("detail", detailSchema, detail));
    tx.executeWithoutResult(
        status ->
            catalog.admit(TENANT, List.of(scope(empty, parent), scope(detailSchema, detail))));
    tx.executeWithoutResult(
        status ->
            em.createNativeQuery(
                    "UPDATE {h-schema}simpoint_dna_relation_definitions SET"
                        + " source_root_model_id='customer'")
                .executeUpdate());
    rejected(
        () -> tx.executeWithoutResult(status -> catalog.admitModels(TENANT, List.of("line"))),
        DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED);
  }

  @Test
  void admittedTransactionBlocksPublicationAndOtherTenantsRemainIndependent() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var publisherPid = new AtomicInteger();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var read =
          workers.submit(
              () ->
                  tx.execute(
                      status -> {
                        catalog.admit(TENANT, List.of(scope(empty, target)));
                        entered.countDown();
                        try {
                          assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException failure) {
                          throw new IllegalStateException(failure);
                        }
                        return "target committed";
                      }));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var publication =
            workers.submit(
                () ->
                    tx.execute(
                        status -> {
                          publisherPid.set(
                              ((Number)
                                      em.createNativeQuery("select pg_backend_pid()")
                                          .getSingleResult())
                                  .intValue());
                          catalog.write(TENANT);
                          catalog.prepare(
                              TENANT,
                              "publisher",
                              em.find(SchemaMigrationEntity.class, "migration"),
                              sourceSchema,
                              sourceTarget);
                          return "guard persisted";
                        }));
        awaitDatabaseLock(publisherPid);
        tx.executeWithoutResult(
            status -> {
              boundary.write("tenant-b");
            });
        assertThat(publication.isDone()).isFalse();
        release.countDown();
        assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo("target committed");
        assertThat(publication.get(5, TimeUnit.SECONDS)).isEqualTo("guard persisted");
      } finally {
        release.countDown();
      }
    }
    rejected(
        () ->
            tx.executeWithoutResult(status -> catalog.admit(TENANT, List.of(scope(empty, target)))),
        DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
  }

  @Test
  void boundaryRejectsMissingInstallationAndReadToWriteUpgrade() {
    assertThatThrownBy(() -> boundary.read(TENANT)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> boundary.read("missing")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      boundary.read(TENANT);
                      boundary.write(TENANT);
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot upgrade");
    assertThat(tx.<Long>execute(status -> boundary.read(TENANT))).isEqualTo(1L);
  }

  private void awaitDatabaseLock(AtomicInteger pid) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try (var connection = DriverManager.getConnection(url, user, password);
        var statement =
            connection.prepareStatement(
                "SELECT wait_event_type FROM pg_stat_activity WHERE pid=?")) {
      while (System.nanoTime() < deadline) {
        statement.setInt(1, pid.get());
        try (var rows = statement.executeQuery()) {
          if (rows.next() && "Lock".equals(rows.getString(1))) {
            return;
          }
        }
        Thread.sleep(20);
      }
    }
    throw new AssertionError("Publication did not reach the catalog row lock");
  }

  private void prepare() {
    tx.executeWithoutResult(
        status -> {
          catalog.write(TENANT);
          catalog.prepare(
              TENANT,
              "publisher",
              em.find(SchemaMigrationEntity.class, "migration"),
              sourceSchema,
              sourceTarget);
        });
  }

  private void activate(
      String id, PhysicalSchemaDefinition schema, PhysicalSchemaTarget destination) {
    catalog.write(TENANT);
    var migration = em.find(SchemaMigrationEntity.class, id);
    migration.setStatus(SchemaMigrationStatus.VERIFYING);
    catalog.activate(TENANT, "publisher", migration, schema, destination);
    migration.setStatus(SchemaMigrationStatus.ACTIVE);
    var model = em.find(PhysicalModelEntity.class, migration.getPhysicalModelId());
    model.setActiveVersion(migration.getTargetVersion());
    model.setStatus(PhysicalModelStatus.ACTIVE);
  }

  private PhysicalRelationCatalog newCatalog() {
    var repositories = new JpaRepositoryFactory(em);
    var versions = repositories.getRepository(PhysicalModelVersionRepository.class);
    var bindings = repositories.getRepository(PhysicalModelBindingRepository.class);
    return new PhysicalRelationCatalog(
        em,
        boundary,
        versions,
        bindings,
        codec,
        new PhysicalReferenceResolver(
            repositories.getRepository(PhysicalModelRepository.class), bindings, versions, codec));
  }

  private void seedModel(
      String id,
      String tenant,
      PhysicalSchemaDefinition schema,
      PhysicalSchemaTarget storage,
      boolean active) {
    var model = new PhysicalModelEntity();
    model.setId(id);
    model.setTenantId(tenant);
    model.setAppId("app");
    model.setFormId(id);
    model.setDisplayName(id);
    model.setLatestVersion(1);
    model.setActiveVersion(active ? 1 : null);
    model.setStatus(active ? PhysicalModelStatus.ACTIVE : PhysicalModelStatus.VALIDATING);
    em.persist(model);
    var binding = new PhysicalModelBindingEntity();
    binding.setId(UUID.randomUUID().toString());
    binding.setTenantId(tenant);
    binding.setPhysicalModelId(id);
    binding.setDataSourceId(storage.metadataTarget().dataSourceId());
    binding.setCatalogName(storage.metadataTarget().catalogName());
    binding.setSchemaName(storage.metadataTarget().schemaName());
    binding.setTableName(storage.metadataTarget().tableName());
    binding.setIsolationMode(PhysicalIsolationMode.SHARED_DATABASE);
    em.persist(binding);
    seedVersion(id, tenant, 1, schema);
  }

  private void seedVersion(
      String model, String tenant, int version, PhysicalSchemaDefinition schema) {
    var encoded = codec.encode(schema);
    var row = new PhysicalModelVersionEntity();
    row.setId(UUID.randomUUID().toString());
    row.setTenantId(tenant);
    row.setPhysicalModelId(model);
    row.setVersionNumber(version);
    row.setContractVersion(schema.contractVersion());
    row.setSchemaJson(encoded.json());
    row.setSchemaChecksum(encoded.checksum());
    em.persist(row);
  }

  private SchemaMigrationEntity migration(String id, String model, Integer previous, int version) {
    var result = new SchemaMigrationEntity();
    result.setId(id);
    result.setTenantId(TENANT);
    result.setPhysicalModelId(model);
    result.setFromVersion(previous);
    result.setTargetVersion(version);
    result.setTargetSchemaChecksum("a".repeat(64));
    result.setExecutionToken(UUID.randomUUID().toString());
    result.setStatus(SchemaMigrationStatus.VALIDATING);
    result.setStartedAt(Instant.now());
    result.setPlanJson("{}");
    result.setPlanChecksum("b".repeat(64));
    return result;
  }

  private PhysicalSchemaDefinition sourceSchema(String column) {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(
            new LogicalFieldDefinition(
                "customer", column, "客户", LogicalFieldType.UUID, true, null, null, null, null)),
        List.of(),
        null,
        true,
        List.of(new LogicalReferenceDefinition("customer-trades", "customer", "customer", false)));
  }

  private PhysicalRelationCatalog.Scope scope(
      PhysicalSchemaDefinition schema, PhysicalSchemaTarget target) {
    return new PhysicalRelationCatalog.Scope(1, schema, target);
  }

  private long count(String entity) {
    return tx.execute(
        status ->
            em.createQuery("select count(r) from " + entity + " r", Long.class).getSingleResult());
  }

  private static void rejected(Runnable action, DnaRuntimeErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class, failure -> assertThat(failure.errorCode()).isEqualTo(code));
  }
}
