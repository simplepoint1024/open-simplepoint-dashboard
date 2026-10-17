package org.simplepoint.plugin.dna.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.Session;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelResourceBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalResourceProjectionEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelResourceBindingRepository;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Real transactions exercise fencing, legacy ownership, row reuse and first-insert contention. */
class PhysicalResourceProjectionDatabaseTest {
  @Test
  void managesResourceGenerationsOnH2() throws Exception {
    run("jdbc:h2:mem:projection_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "", false);
  }

  @Test
  void productionMigrationAndConcurrentProjectionWorkOnPostgresql() throws Exception {
    String url = System.getenv("DNA_RUNTIME_TEST_POSTGRES_URL");
    assumeTrue(url != null && !url.isBlank(), "Real PostgreSQL control database is required");
    run(url, System.getenv("DNA_RUNTIME_TEST_POSTGRES_USER"), System.getenv("DNA_RUNTIME_TEST_POSTGRES_PASSWORD"), true);
  }

  private void run(String url, String user, String password, boolean postgres) throws Exception {
    String schema = "projection_" + UUID.randomUUID().toString().replace("-", "");
    StandardServiceRegistry registry = null;
    try {
      if (postgres) {
        try (var connection = DriverManager.getConnection(url, user, password); var sql = connection.createStatement()) {
          sql.execute("create schema " + schema);
          sql.execute("set search_path to " + schema);
          Path migrations = Path.of(System.getProperty("simplepoint.dna.migrations"));
          for (String file : List.of("20260929-dna-form-runtime-foundation.sql", "20260930-dna-record-runtime.sql",
              "20261013-dna-record-create-idempotency.sql", "20261013-dna-resource-projections.sql",
              "20261013-dna-resource-projections.sql", "20261015-dna-resource-lifecycle.sql")) {
            sql.execute(Files.readString(migrations.resolve(file)));
          }
        }
      }
      var builder = new StandardServiceRegistryBuilder()
          .applySetting("hibernate.connection.url", url)
          .applySetting("hibernate.connection.username", user)
          .applySetting("hibernate.connection.password", password)
          .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
          .applySetting("hibernate.hbm2ddl.auto", postgres ? "validate" : "create-drop");
      if (postgres) {
        builder.applySetting("hibernate.default_schema", schema);
      }
      registry = builder.build();
      try (var factory = new MetadataSources(registry)
          .addAnnotatedClass(PhysicalModelEntity.class)
          .addAnnotatedClass(PhysicalModelResourceBindingEntity.class)
          .addAnnotatedClass(PhysicalResourceProjectionEntity.class)
          .buildMetadata().buildSessionFactory()) {
        verify(factory);
      }
    } finally {
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
      if (registry != null) {
        StandardServiceRegistryBuilder.destroy(registry);
      }
      if (postgres) {
        try (var connection = DriverManager.getConnection(url, user, password); var sql = connection.createStatement()) {
          sql.execute("drop schema if exists " + schema + " cascade");
        }
      }
    }
  }

  private void verify(EntityManagerFactory factory) throws Exception {
    authorize("tenant-a");
    EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
    var manager = new JpaTransactionManager(factory);
    final var transaction = new TransactionTemplate(manager);
    var repositories = new JpaRepositoryFactory(entityManager);
    var target = new PhysicalModelResourceStateService(entityManager, new RuntimeRequestContext(),
        repositories.getRepository(PhysicalModelRepository.class),
        repositories.getRepository(PhysicalModelResourceBindingRepository.class));
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    var interceptor = new TransactionInterceptor();
    interceptor.setTransactionManager(manager);
    interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    proxy.addAdvice(interceptor);
    var service = new PhysicalModelAccessServiceImpl((PhysicalModelResourceStateService) proxy.getProxy());
    transaction.executeWithoutResult(status -> {
      for (String id : List.of("model-a", "model-b")) {
        var model = new PhysicalModelEntity();
        model.setId(id);
        model.setTenantId("tenant-a");
        model.setAppId("app-a");
        model.setFormId(id);
        model.setDisplayName(id);
        model.setStatus(PhysicalModelStatus.ACTIVE);
        model.setLatestVersion(1);
        model.setActiveVersion(1);
        model.setUpdatedBy("publisher");
        entityManager.persist(model);
      }
    });

    final var old = service.bind(new PhysicalModelResourceBindingCommand("model-a", "page-main", 0));
    service.bind(new PhysicalModelResourceBindingCommand("model-b", "page-main", 0));
    service.bind(new PhysicalModelResourceBindingCommand("model-a", "page-other", 0));
    service.project(command("model-b", "page-main", 1));
    assertThat(service.list("model-a")).extracting(binding -> binding.resourceCode()).containsExactly("page-other");
    var restored = service.project(command("model-a", "page-main", 2));
    assertThat(restored.id()).isEqualTo(old.id());
    assertThat(service.list("model-b")).isEmpty();
    assertThat(service.project(command("model-a", "page-main", 2)).id()).isEqualTo(old.id());
    assertCode(() -> service.project(command("model-b", "page-main", 1)), DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED);
    assertCode(() -> service.project(command("model-b", "page-main", 2)), DnaRuntimeErrorCode.PHYSICAL_BINDING_CONFLICT);
    assertCode(() -> service.project(new PhysicalModelResourceProjectionCommand("model-a", "page-main", "foreign-owner", 3)),
        DnaRuntimeErrorCode.PHYSICAL_BINDING_CONFLICT);
    assertCode(() -> service.unbind(new PhysicalModelResourceBindingCommand("model-a", "page-main", 0)),
        DnaRuntimeErrorCode.PHYSICAL_BINDING_CONFLICT);
    assertCode(() -> service.bind(new PhysicalModelResourceBindingCommand("model-b", "page-main", 0)),
        DnaRuntimeErrorCode.PHYSICAL_BINDING_CONFLICT);

    // Reuse an ordinary retired row even if the platform soft-delete filter is enabled.
    var legacy = service.bind(new PhysicalModelResourceBindingCommand("model-a", "legacy", 0));
    service.unbind(new PhysicalModelResourceBindingCommand("model-a", "legacy", 0));
    transaction.executeWithoutResult(status -> {
      entityManager.unwrap(Session.class).enableFilter("softDeleteFilter");
      // The underlying state uses REQUIRES_NEW, so also verify the explicit native tenant lookup itself.
      assertThat(repositories.getRepository(PhysicalModelResourceBindingRepository.class)
          .findIncludingDeleted("tenant-a", "model-a", "legacy")).isPresent();
    });
    long version = transaction.execute(status -> entityManager.find(PhysicalModelEntity.class, "model-a").getLockVersion());
    assertThat(service.bind(new PhysicalModelResourceBindingCommand("model-a", "legacy", version)).id()).isEqualTo(legacy.id());

    authorize("tenant-b");
    assertCode(() -> service.project(command("model-a", "page-main", 99)), DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND);
    long foreignProjections = transaction.execute(status -> entityManager.createQuery(
        "select count(p) from PhysicalResourceProjectionEntity p where p.tenantId = 'tenant-b'", Long.class)
        .getSingleResult());
    assertThat(foreignProjections).isZero();
    authorize("tenant-a");
    assertThat(service.project(command("model-a", "page-main", 2)).id()).isEqualTo(old.id());

    var denied = new AuthorizationContext();
    denied.setUserId("reader");
    denied.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, denied);
    assertThatThrownBy(() -> service.project(command("model-b", "page-main", 100)))
        .isInstanceOf(AccessDeniedException.class);
    authorize("tenant-a");
    transaction.executeWithoutResult(status -> entityManager.find(PhysicalModelEntity.class, "model-b")
        .setStatus(PhysicalModelStatus.RETIRED));
    assertCode(() -> service.project(command("model-b", "page-main", 3)), DnaRuntimeErrorCode.PHYSICAL_BINDING_CONFLICT);
    assertThat(service.project(command("model-a", "page-main", 2)).id()).isEqualTo(old.id());
    transaction.executeWithoutResult(status -> entityManager.find(PhysicalModelEntity.class, "model-b")
        .setStatus(PhysicalModelStatus.ACTIVE));

    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var lower = pool.submit(() -> {
        authorize("tenant-a");
        try {
          gate.await();
          try {
            service.project(command("model-a", "page-race", 1));
          } catch (DnaRuntimeException failure) {
            assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED);
          }
          return true;
        } finally {
          RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
        }
      });
      var higher = pool.submit(() -> {
        authorize("tenant-a");
        try {
          gate.await();
          return service.project(command("model-b", "page-race", 2));
        } finally {
          RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
        }
      });
      gate.countDown();
      assertThat(lower.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(higher.get(20, TimeUnit.SECONDS).physicalModelId()).isEqualTo("model-b");
    }
    assertCode(() -> service.project(command("model-a", "page-race", 1)), DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED);
    assertThat(service.list("model-a")).noneMatch(binding -> binding.resourceCode().equals("page-race"));
    assertThat(service.list("model-b")).anyMatch(binding -> binding.resourceCode().equals("page-race"));
  }

  private static PhysicalModelResourceProjectionCommand command(String model, String resource, long generation) {
    return new PhysicalModelResourceProjectionCommand(model, resource, "app:" + resource, generation);
  }

  private static void authorize(String tenant) {
    var actor = new AuthorizationContext();
    actor.setUserId("publisher");
    actor.setIsAdministrator(true);
    actor.setAttributes(Map.of("X-Tenant-Id", tenant));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, actor);
  }

  private static void assertCode(Runnable action, DnaRuntimeErrorCode code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(code));
  }
}
