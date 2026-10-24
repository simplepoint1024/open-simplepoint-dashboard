package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.locked;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.update;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.platform.resource.ResourceLifecycleEvents;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditPublisher;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingTarget;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalIsolationMode;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelRegistrationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionCommand;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelResourceBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalResourceProjectionEntity;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationEntity;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationStepEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelResourceBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.repository.SchemaMigrationRepository;
import org.simplepoint.plugin.dna.runtime.repository.SchemaMigrationStepRepository;
import org.simplepoint.plugin.dna.runtime.service.impl.PhysicalModelResourceStateService;
import org.simplepoint.plugin.dna.runtime.service.impl.PhysicalModelServiceImpl;
import org.simplepoint.plugin.dna.runtime.service.impl.PhysicalResourceOfflineHandler;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService;
import org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.Database;
import org.simplepoint.plugin.dna.runtime.service.support.DefaultPhysicalTableNamingStrategy;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaValidator;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** A real PostgreSQL control DB fences distinct PostgreSQL/MySQL physical target transactions. */
class RecordMaintenanceControlDatabaseTest {
  private static final String TENANT = "tenant-a";
  private static final RecordPublicationStore JOURNAL = new RecordPublicationStore();

  @Test
  void serializesControlMetadataAndPostgresqlMaintenance() throws Exception {
    RecordPublicationPatchDatabaseTest.verify(
        "POSTGRES", true, RecordMaintenanceControlDatabaseTest::withControl);
  }

  @Test
  void serializesPostgresqlControlMetadataAndMysqlMaintenance() throws Exception {
    RecordPublicationPatchDatabaseTest.verify(
        "MYSQL", false, RecordMaintenanceControlDatabaseTest::withControl);
  }

  private static void withControl(Database db) throws Exception {
    withControl(
        db,
        fixture ->
            verify(
                db,
                fixture.em(),
                fixture.transactions(),
                fixture.maintenance(),
                fixture.control(),
                fixture.resource(),
                fixture.physical(),
                fixture.publication(),
                fixture.offline(),
                fixture.events()));
  }

  static void withControl(Database db, ControlScenario scenario) throws Exception {
    String url = System.getenv("DNA_RUNTIME_TEST_POSTGRES_URL");
    String user = System.getenv("DNA_RUNTIME_TEST_POSTGRES_USER");
    String password = System.getenv("DNA_RUNTIME_TEST_POSTGRES_PASSWORD");
    String schema = "maintenance_control_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password);
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    }
    // Match Boot's Hibernate JPA adapter, including transaction-specific JDBC isolation support.
    var vendor = new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter();
    var registry =
        new StandardServiceRegistryBuilder()
            .applySettings(vendor.getJpaPropertyMap())
            .applySetting("hibernate.connection.url", url)
            .applySetting("hibernate.connection.username", user)
            .applySetting("hibernate.connection.password", password)
            .applySetting("hibernate.default_schema", schema)
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .applySetting(
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
            .build();
    try {
      migrateControlSchema(url, user, password, schema);
      try (var factory =
          new MetadataSources(registry)
              .addAnnotatedClass(PhysicalModelEntity.class)
              .addAnnotatedClass(PhysicalModelBindingEntity.class)
              .addAnnotatedClass(PhysicalModelVersionEntity.class)
              .addAnnotatedClass(PhysicalModelResourceBindingEntity.class)
              .addAnnotatedClass(PhysicalResourceProjectionEntity.class)
              .addAnnotatedClass(SchemaMigrationEntity.class)
              .addAnnotatedClass(SchemaMigrationStepEntity.class)
              .buildMetadata()
              .buildSessionFactory()) {
        var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        var manager = new JpaTransactionManager(factory);
        manager.setJpaDialect(vendor.getJpaDialect());
        var transactions = new TransactionTemplate(manager);
        var repositories = new JpaRepositoryFactory(em);
        var models = repositories.getRepository(PhysicalModelRepository.class);
        var bindings = repositories.getRepository(PhysicalModelBindingRepository.class);
        var versions = repositories.getRepository(PhysicalModelVersionRepository.class);
        var maintenance =
            new RecordMaintenanceControl(
                em, models, bindings, versions, db.fixture().codec, db.backend());
        var control = new RecordPublicationControlFence(maintenance, manager);
        transactions.executeWithoutResult(status -> seed(em, db));
        try (var connection = RecordPublicationPatchDatabaseTest.transaction(db)) {
          db.fence().initialize(connection, db.fixture().intent.key(), "publisher", 30_000);
          connection.commit();
        }
        var context = new RuntimeRequestContext();
        var audit = mock(DnaRuntimeAuditPublisher.class);
        var resource =
            proxy(
                new PhysicalModelResourceStateService(
                    em,
                    context,
                    models,
                    repositories.getRepository(PhysicalModelResourceBindingRepository.class),
                    maintenance),
                manager);
        var physical =
            proxy(
                new PhysicalModelServiceImpl(
                    models,
                    bindings,
                    versions,
                    request ->
                        new PhysicalBindingTarget(
                            "source",
                            db.catalog(),
                            db.schema(),
                            PhysicalIsolationMode.SHARED_DATABASE),
                    new DefaultPhysicalTableNamingStrategy(),
                    db.fixture().codec,
                    new PhysicalSchemaValidator(new StandardLogicalValueConverter()),
                    context,
                    audit,
                    maintenance),
                manager);
        var publication =
            proxy(
                new SchemaPublicationStateService(
                    models,
                    bindings,
                    versions,
                    repositories.getRepository(SchemaMigrationRepository.class),
                    repositories.getRepository(SchemaMigrationStepRepository.class),
                    db.fixture().codec,
                    audit,
                    maintenance),
                manager);
        var events = mock(EventPublisher.class);
        var offline = new PhysicalResourceOfflineHandler(em, events, maintenance);
        scenario.run(
            new ControlFixture(
                em,
                transactions,
                maintenance,
                control,
                resource,
                physical,
                publication,
                offline,
                events));
      }
    } finally {
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
      StandardServiceRegistryBuilder.destroy(registry);
      try (var connection = DriverManager.getConnection(url, user, password);
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  private static void migrateControlSchema(
      String url, String user, String password, String schema) {
    var source = new DriverManagerDataSource(url, user, password);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema);
    source.setConnectionProperties(properties);
    var modules =
        ModuleCatalog.discover(RecordMaintenanceControlDatabaseTest.class.getClassLoader())
            .plan(List.of("dna-runtime"));
    var binding = new ModuleStorageBindings.Binding("default", "source", "emf", "em", "tx", "jdbc");
    var bindings = new LinkedHashMap<String, ModuleStorageBindings.Binding>();
    modules.modules().forEach(module -> bindings.put(module.id(), binding));
    var plan = ModuleMigrationPlan.discover(modules, new ModuleStorageBindings(bindings));
    new ModuleMigrationEngine().migrate(plan, Map.of("default", source));
  }

  private static void verify(
      Database db,
      EntityManager em,
      TransactionTemplate transactions,
      RecordMaintenanceControl maintenance,
      RecordPublicationControlFence control,
      PhysicalModelResourceStateService resource,
      PhysicalModelServiceImpl physical,
      SchemaPublicationStateService publication,
      PhysicalResourceOfflineHandler offline,
      EventPublisher events)
      throws Exception {
    authorize();
    var parent = db.fixture().parent;
    final var child = db.fixture().child;
    final var ids = db.fixture().intent.bindings().keySet();
    resource.bind(
        new PhysicalModelResourceBindingCommand(
            parent.physicalModelId(), "page", version(transactions, em, parent)));
    resource.bind(
        new PhysicalModelResourceBindingCommand(
            parent.physicalModelId(), "legacy", version(transactions, em, parent)));
    resource.project(
        new PhysicalModelResourceProjectionCommand(
            parent.physicalModelId(), "page", "app:page", 1));
    // Unlocked control metadata is never a valid inspection boundary.
    transactions.executeWithoutResult(
        status ->
            assertThatThrownBy(
                    () ->
                        maintenance.requireAvailable(
                            TENANT, em.find(PhysicalModelEntity.class, parent.physicalModelId())))
                .isInstanceOf(IllegalStateException.class));
    assertThatThrownBy(() -> maintenance.lockAvailable(TENANT, ids))
        .isInstanceOf(IllegalStateException.class);
    transactions.executeWithoutResult(
        status -> {
          var lockedChild =
              em.find(
                  PhysicalModelEntity.class,
                  child.physicalModelId(),
                  jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
          assertThatThrownBy(() -> maintenance.requireAvailable(TENANT, lockedChild))
              .isInstanceOf(IllegalStateException.class);
        });
    transactions.executeWithoutResult(
        status ->
            assertThat(maintenance.lockAvailable(TENANT, List.of(child.physicalModelId())).keySet())
                .containsExactlyElementsOf(ids.stream().sorted().toList()));
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempting = new CountDownLatch(1);
    var targetEntered = new AtomicBoolean();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var management =
          executor.submit(
              () ->
                  transactions.execute(
                      status -> {
                        var model =
                            maintenance
                                .lockAvailable(TENANT, List.of(parent.physicalModelId()))
                                .get(parent.physicalModelId());
                        model.setStatus(PhysicalModelStatus.DRAFT);
                        held.countDown();
                        await(release);
                        return true;
                      }));
      try {
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        var publish =
            executor.submit(
                () -> {
                  attempting.countDown();
                  return control.execute(
                      TENANT,
                      ids,
                      locked -> {
                        targetEntered.set(true);
                        return true;
                      });
                });
        assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> publish.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        release.countDown();
        assertThat(management.get(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> publish.get(10, TimeUnit.SECONDS))
            .hasCauseInstanceOf(DnaRuntimeException.class);
        assertThat(targetEntered).isFalse();
      } finally {
        release.countDown();
      }
    }
    transactions.executeWithoutResult(
        status ->
            em.find(PhysicalModelEntity.class, parent.physicalModelId())
                .setStatus(PhysicalModelStatus.ACTIVE));
    long expected = version(transactions, em, parent);
    var entered = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    var changing = new CountDownLatch(1);
    var initial =
        new RecordPublicationStore.Entry(db.fixture().intent, RecordPublicationCheckpoint.start(0));
    try (var executor = Executors.newFixedThreadPool(2)) {
      var publish =
          executor.submit(
              () ->
                  control.execute(
                      TENANT,
                      ids,
                      locked -> {
                        entered.countDown();
                        await(finish);
                        try (var connection = locked(db)) {
                          assertThat(update(db, connection, JOURNAL.insert(initial))).isEqualTo(1);
                          connection.commit();
                        } catch (Exception failure) {
                          throw new AssertionError(failure);
                        }
                        throw new IllegalStateException(
                            "Simulated control rollback after durable target claim");
                      }));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        var change =
            executor.submit(
                () -> {
                  authorize();
                  try {
                    changing.countDown();
                    blocked(
                        () ->
                            physical.createVersion(
                                parent.physicalModelId(),
                                new PhysicalModelVersionCommand(expected, parent.activeSchema())));
                    return true;
                  } finally {
                    RequestContextHolder.clearContext(
                        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
                  }
                });
        assertThat(changing.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> change.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        finish.countDown();
        assertThatThrownBy(() -> publish.get(10, TimeUnit.SECONDS))
            .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(change.get(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        finish.countDown();
      }
    }
    assertThat(version(transactions, em, parent)).isEqualTo(expected);
    blocked(
        () ->
            physical.createVersion(
                child.physicalModelId(),
                new PhysicalModelVersionCommand(
                    version(transactions, em, child), child.activeSchema())));
    blocked(
        () ->
            physical.register(
                new PhysicalModelRegistrationCommand(
                    parent.appId(), "new-child", "新明细", "default", child.activeSchema())));
    blocked(
        () ->
            resource.bind(
                new PhysicalModelResourceBindingCommand(
                    parent.physicalModelId(), "new-page", expected)));
    blocked(
        () ->
            resource.unbind(
                new PhysicalModelResourceBindingCommand(
                    parent.physicalModelId(), "legacy", expected)));
    blocked(
        () ->
            resource.project(
                new PhysicalModelResourceProjectionCommand(
                    child.physicalModelId(), "page", "app:page", 2)));
    transactions.executeWithoutResult(
        status ->
            em.find(PhysicalModelEntity.class, child.physicalModelId())
                .setStatus(PhysicalModelStatus.DRAFT));
    blocked(
        () ->
            publication.claim(
                new RuntimeRequestContext.RuntimeActor(TENANT, "publisher"),
                child.physicalModelId(),
                new SchemaPublicationCommand(2, version(transactions, em, child))));
    String migrationId = UUID.randomUUID().toString();
    transactions.executeWithoutResult(
        status -> {
          em.find(PhysicalModelEntity.class, child.physicalModelId())
              .setStatus(PhysicalModelStatus.FAILED);
          var migration = new SchemaMigrationEntity();
          migration.setId(migrationId);
          migration.setTenantId(TENANT);
          migration.setPhysicalModelId(child.physicalModelId());
          migration.setFromVersion(1);
          migration.setTargetVersion(2);
          migration.setTargetSchemaChecksum(
              db.fixture().codec.encode(child.activeSchema()).checksum());
          migration.setStatus(
              org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus.FAILED);
          migration.setExecutionToken("original-token");
          migration.setStartedAt(Instant.now());
          em.persist(migration);
        });
    blocked(
        () ->
            publication.claimRetry(
                new RuntimeRequestContext.RuntimeActor(TENANT, "publisher"),
                migrationId,
                version(transactions, em, child)));
    transactions.executeWithoutResult(
        status -> {
          var migration = em.find(SchemaMigrationEntity.class, migrationId);
          assertThat(migration.getExecutionToken()).isEqualTo("original-token");
          assertThat(migration.getStatus())
              .isEqualTo(
                  org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus.FAILED);
          em.remove(migration);
          em.find(PhysicalModelEntity.class, child.physicalModelId())
              .setStatus(PhysicalModelStatus.ACTIVE);
        });
    var event =
        new EventMessage(
            UUID.randomUUID(),
            ResourceLifecycleEvents.OFFLINE_REQUESTED,
            1,
            "app",
            TENANT,
            "page",
            2,
            UUID.randomUUID().toString(),
            "maintenance-test",
            Instant.now(),
            JsonNodeFactory.instance
                .objectNode()
                .put("managedOwner", "app")
                .put("managedKey", "page")
                .put("resourceCode", "page"));
    blocked(() -> transactions.executeWithoutResult(status -> offline.handle(event)));
    verifyNoInteractions(events);
    assertThat(resource.list(parent.physicalModelId()))
        .extracting(binding -> binding.resourceCode())
        .containsExactlyInAnyOrder("legacy", "page");
    transactions.executeWithoutResult(
        status -> {
          assertThat(
                  em.createQuery("select count(m) from SchemaMigrationEntity m", Long.class)
                      .getSingleResult())
              .isZero();
          assertThat(
                  em.createQuery("select count(m) from PhysicalModelEntity m", Long.class)
                      .getSingleResult())
              .isEqualTo(2);
        });
    // Trusted target cancellation completes first. Management can then proceed without cached
    // blockers.
    control.execute(
        TENANT,
        ids,
        locked -> {
          try (var connection = locked(db)) {
            var restoring =
                initial.checkpoint().transition(RecordPublicationCheckpoint.Phase.RESTORING);
            assertThat(
                    update(
                        db,
                        connection,
                        JOURNAL.advance(initial, restoring, db.fixture().intent.clock())))
                .isEqualTo(1);
            assertThat(
                    update(
                        db,
                        connection,
                        JOURNAL.advance(
                            new RecordPublicationStore.Entry(initial.intent(), restoring),
                            restoring.transition(RecordPublicationCheckpoint.Phase.CANCELLED),
                            initial.intent().clock())))
                .isEqualTo(1);
            db.fence()
                .cancel(
                    connection,
                    initial.intent().key(),
                    0,
                    1,
                    initial.intent().fingerprint(),
                    "e".repeat(64),
                    "publisher",
                    new RecordExecutionDeadline(30_000));
            connection.commit();
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
          return true;
        });
    transactions.executeWithoutResult(status -> offline.handle(event));
    org.mockito.Mockito.verify(events)
        .publish(
            org.mockito.ArgumentMatchers.argThat(
                message -> message.eventType().equals(ResourceLifecycleEvents.OFFLINE_COMPLETED)));
    assertThat(resource.list(parent.physicalModelId()))
        .extracting(binding -> binding.resourceCode())
        .containsExactly("legacy");
    var updated =
        physical.createVersion(
            parent.physicalModelId(),
            new PhysicalModelVersionCommand(
                version(transactions, em, parent), parent.activeSchema()));
    assertThat(updated.version()).isEqualTo(3);
  }

  private static void seed(EntityManager em, Database db) {
    for (var model : List.of(db.fixture().parent, db.fixture().child)) {
      var entity = new PhysicalModelEntity();
      entity.setId(model.physicalModelId());
      entity.setTenantId(TENANT);
      entity.setAppId(model.appId());
      entity.setFormId(model.formId());
      entity.setDisplayName(model.formId());
      entity.setStatus(PhysicalModelStatus.ACTIVE);
      entity.setLatestVersion(2);
      entity.setActiveVersion(2);
      entity.setCreatedBy("publisher");
      entity.setUpdatedBy("publisher");
      em.persist(entity);
      var binding = new PhysicalModelBindingEntity();
      binding.setId(UUID.randomUUID().toString());
      binding.setTenantId(TENANT);
      binding.setPhysicalModelId(entity.getId());
      var target = model.target().metadataTarget();
      binding.setDataSourceId(target.dataSourceId());
      binding.setCatalogName(target.catalogName());
      binding.setSchemaName(target.schemaName());
      binding.setTableName(target.tableName());
      binding.setIsolationMode(PhysicalIsolationMode.SHARED_DATABASE);
      em.persist(binding);
      for (int number : List.of(1, 2)) {
        var version = new PhysicalModelVersionEntity();
        version.setId(UUID.randomUUID().toString());
        version.setTenantId(TENANT);
        version.setPhysicalModelId(entity.getId());
        version.setVersionNumber(number);
        version.setContractVersion("1");
        var schema =
            db.fixture().codec.encode(number == 1 ? model.requestedSchema() : model.activeSchema());
        version.setSchemaJson(schema.json());
        version.setSchemaChecksum(schema.checksum());
        em.persist(version);
      }
    }
  }

  private static long version(
      TransactionTemplate transactions, EntityManager em, RuntimeRecordModel model) {
    return transactions.execute(
        status -> em.find(PhysicalModelEntity.class, model.physicalModelId()).getLockVersion());
  }

  static void authorize() {
    var context = new AuthorizationContext();
    context.setUserId("publisher");
    context.setIsAdministrator(true);
    context.setAttributes(Map.of("X-Tenant-Id", TENANT));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private static void blocked(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS));
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(T target, JpaTransactionManager manager) {
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    var interceptor = new TransactionInterceptor();
    interceptor.setTransactionManager(manager);
    interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    proxy.addAdvice(interceptor);
    return (T) proxy.getProxy();
  }

  @FunctionalInterface
  interface ControlScenario {
    void run(ControlFixture fixture) throws Exception;
  }

  record ControlFixture(
      EntityManager em,
      TransactionTemplate transactions,
      RecordMaintenanceControl maintenance,
      RecordPublicationControlFence control,
      PhysicalModelResourceStateService resource,
      PhysicalModelServiceImpl physical,
      SchemaPublicationStateService publication,
      PhysicalResourceOfflineHandler offline,
      EventPublisher events) {}

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new AssertionError("Control test latch timed out");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }
}
