package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.resource.base.repository.JpaResourceAncestorRepository;
import org.simplepoint.plugin.rbac.resource.base.repository.JpaResourceRepository;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceCatalogWriteBoundary;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationDeployments;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceAncestor;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.service.ResourceService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared real catalog repositories; creates only an isolated test schema. */
abstract class ResourceCatalogTestSupport {
  protected SessionFactory factory;
  protected EntityManager em;
  protected TransactionTemplate transaction;
  protected ResourceService service;
  protected final org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects
      catalogEffects =
          mock(org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects.class);

  protected JpaTransactionManager manager;
  protected ResourceCatalogWriteBoundary boundary;
  protected ResourceRegistrationDeployments deployments;

  @BeforeEach
  void start() throws Exception {
    String postgres = System.getenv("SP_RESOURCE_TEST_POSTGRES_URL");
    var settings =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .applySetting("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD");
    if (postgres == null) {
      settings.applySetting(
          "hibernate.connection.url", "jdbc:h2:mem:resource-sync-" + UUID.randomUUID());
    } else {
      if (!postgres.equals("jdbc:postgresql://127.0.0.1:45436/resource_registration_test")) {
        throw new IllegalArgumentException("Resource tests require the isolated PostgreSQL URL");
      }
      settings
          .applySetting("hibernate.connection.url", postgres)
          .applySetting("hibernate.connection.username", "resource_test")
          .applySetting(
              "hibernate.connection.password", System.getenv("SP_RESOURCE_TEST_POSTGRES_PASSWORD"));
    }
    factory =
        new MetadataSources(settings.build())
            .addAnnotatedClass(Resource.class)
            .addAnnotatedClass(ResourceAncestor.class)
            .addAnnotatedClass(DataScope.class)
            .buildMetadata()
            .buildSessionFactory();
    em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    manager = new JpaTransactionManager(factory);
    manager.setJpaDialect(new org.springframework.orm.jpa.vendor.HibernateJpaDialect());
    transaction = new TransactionTemplate(manager);
    boundary = new ResourceCatalogWriteBoundary(em);
    String migration;
    try (var stream =
        getClass()
            .getResourceAsStream(
                "/META-INF/simplepoint/modules/identity/db/postgresql/V4__resource_catalog_revision.sql")) {
      migration =
          new String(
              java.util.Objects.requireNonNull(stream).readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
    }
    transaction.executeWithoutResult(
        status -> {
          em.createNativeQuery("drop table if exists simpoint_resource_catalog_revision")
              .executeUpdate();
          for (String statement : migration.split(";")) {
            if (!statement.isBlank()) {
              em.createNativeQuery(statement).executeUpdate();
            }
          }
        });
    String deploymentMigration;
    try (var stream =
        getClass()
            .getResourceAsStream(
                "/META-INF/simplepoint/modules/identity/db/postgresql/V5__resource_registration_deployments.sql")) {
      deploymentMigration =
          new String(
              java.util.Objects.requireNonNull(stream).readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
    }
    transaction.executeWithoutResult(
        status -> {
          em.createNativeQuery("drop table if exists simpoint_resource_registration_deployments")
              .executeUpdate();
          em.createNativeQuery("drop table if exists simpoint_resource_registration_operations")
              .executeUpdate();
          for (String statement : deploymentMigration.split(";")) {
            if (!statement.isBlank()) {
              em.createNativeQuery(statement).executeUpdate();
            }
          }
        });
    var policy =
        new org.simplepoint.plugin.rbac.resource.service.properties
            .ResourceRegistrationProperties();
    policy.setOwnerClients(java.util.Map.of("owner", "legacy-client"));
    deployments =
        transactional(
            new ResourceRegistrationDeployments(
                em,
                boundary,
                new org.simplepoint.plugin.rbac.resource.service.support
                    .ResourceRegistrationCallerPolicy(policy)));
    service = newService();
  }

  protected ResourceService newService() {
    var repositories = new JpaRepositoryFactory(em);
    repositories.setRepositoryBaseClass(BaseRepositoryImpl.class);
    var resources = repositories.getRepository(JpaResourceRepository.class);
    var ancestors = new JpaRepositoryFactory(em).getRepository(JpaResourceAncestorRepository.class);
    var target =
        new ResourceServiceImpl(
            resources,
            mock(DetailsProviderService.class),
            ancestors,
            mock(MicroAppService.class),
            empty(),
            empty(),
            em,
            new ResourceCatalogWriteBoundary(em),
            deployments,
            catalogEffects);
    return transactional(target);
  }

  @SuppressWarnings("unchecked")
  protected <T> T transactional(T target) {
    var interceptor = new TransactionInterceptor();
    interceptor.setTransactionManager(manager);
    interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    var proxy = new ProxyFactory(target);
    proxy.addAdvice(interceptor);
    proxy.setProxyTargetClass(true);
    return (T) proxy.getProxy();
  }

  @AfterEach
  void close() {
    if (factory != null) {
      factory.close();
    }
  }

  protected Resource resource(String code) {
    return read(
        () ->
            em.createQuery("from Resource where code=:code", Resource.class)
                .setParameter("code", code)
                .getSingleResult());
  }

  protected <T> T read(Supplier<T> action) {
    return transaction.execute(status -> action.get());
  }

  protected static ResourceDeclaration declaration(String code, String path, String name) {
    var declaration = new ResourceDeclaration();
    declaration.setCode(code);
    declaration.setPath(path);
    declaration.setName(name);
    declaration.setScopeTypes(Set.of(ResourceScopeType.PLATFORM));
    return declaration;
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> empty() {
    return mock(ObjectProvider.class);
  }

  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = empty();
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
