package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.mockito.Mockito.mock;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryDeclaration;
import org.simplepoint.platform.dictionary.DictionaryItemDeclaration;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.repository.JpaDictionaryItemRepository;
import org.simplepoint.plugin.rbac.tenant.repository.JpaDictionaryRepository;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryDeclarationWriter;
import org.simplepoint.plugin.rbac.tenant.service.support.DictionaryCatalogWriteBoundary;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared actual dictionary repositories and migrations; only schema/audit collaborators are
 * doubles.
 */
abstract class DictionaryPersistenceTestSupport {
  protected SessionFactory factory;
  protected EntityManager em;
  protected JpaTransactionManager manager;
  protected TransactionTemplate transaction;
  protected DictionaryCatalogWriteBoundary boundary;
  protected DictionaryServiceImpl dictionaries;
  protected DictionaryItemServiceImpl items;
  protected DictionaryDeclarationWriter writer;
  protected org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryRegistrationStore
      registrations;
  protected org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryRegistrationApplication
      application;
  protected org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryRegistrationProvider
      provider;

  @BeforeEach
  void start() throws Exception {
    var settings =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .applySetting(
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
            .applySetting("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD");
    String url = System.getenv("SP_DICTIONARY_TEST_POSTGRES_URL");
    if (url == null) {
      settings.applySetting(
          "hibernate.connection.url", "jdbc:h2:mem:dictionary-" + UUID.randomUUID());
    } else {
      if (!url.equals("jdbc:postgresql://127.0.0.1:45436/dictionary_test")) {
        throw new IllegalArgumentException("Dictionary tests require the isolated PostgreSQL URL");
      }
      settings
          .applySetting("hibernate.connection.url", url)
          .applySetting("hibernate.connection.username", "resource_test")
          .applySetting(
              "hibernate.connection.password",
              System.getenv("SP_DICTIONARY_TEST_POSTGRES_PASSWORD"));
    }
    factory =
        new MetadataSources(settings.build())
            .addAnnotatedClass(Dictionary.class)
            .addAnnotatedClass(DictionaryItem.class)
            .buildMetadata()
            .buildSessionFactory();
    em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    manager = new JpaTransactionManager(factory);
    manager.setJpaDialect(new org.springframework.orm.jpa.vendor.HibernateJpaDialect());
    transaction = new TransactionTemplate(manager);
    boundary = new DictionaryCatalogWriteBoundary(em);
    String migration;
    try (var stream =
        getClass()
            .getResourceAsStream(
                "/META-INF/simplepoint/modules/identity/db/postgresql/V6__dictionary_catalog_revision.sql")) {
      migration =
          new String(
              java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
    transaction.executeWithoutResult(
        status -> {
          em.createNativeQuery("drop table if exists simpoint_dictionary_catalog_revision")
              .executeUpdate();
          for (String statement : migration.split(";")) {
            if (!statement.isBlank()) {
              em.createNativeQuery(statement).executeUpdate();
            }
          }
        });
    var repos = new JpaRepositoryFactory(em);
    repos.setRepositoryBaseClass(BaseRepositoryImpl.class);
    transaction.executeWithoutResult(
        status -> {
          for (String table :
              List.of(
                  "simpoint_dictionary_legacy_adoptions",
                  "simpoint_dictionary_registration_owners",
                  "simpoint_dictionary_registration_approvals",
                  "simpoint_dictionary_registration_operations")) {
            em.createNativeQuery("drop table if exists " + table).executeUpdate();
          }
        });
    try (var stream =
        getClass()
            .getResourceAsStream(
                "/META-INF/simplepoint/modules/identity/db/postgresql/V7__dictionary_registration_authority.sql")) {
      String authority =
          new String(
              java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
      transaction.executeWithoutResult(
          status -> {
            for (String statement : authority.split(";")) {
              if (!statement.isBlank()) {
                em.createNativeQuery(statement).executeUpdate();
              }
            }
          });
    }
    try (var stream =
        getClass()
            .getResourceAsStream(
                "/META-INF/simplepoint/modules/identity/db/postgresql/V8__dictionary_legacy_adoption.sql")) {
      String sql =
          new String(
              java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
      transaction.executeWithoutResult(
          status -> {
            for (String statement : sql.split(";")) {
              if (!statement.isBlank()) {
                em.createNativeQuery(statement).executeUpdate();
              }
            }
          });
    }
    registrations =
        transactional(
            new org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryRegistrationStore(
                em, boundary));
    final var dictionaryRepository = repos.getRepository(JpaDictionaryRepository.class);
    final var itemRepository = repos.getRepository(JpaDictionaryItemRepository.class);
    var details = mock(DetailsProviderService.class);
    org.simplepoint.plugin.rbac.tenant.service.support.BaseServiceSchemaTestSupport
        .stubBaseServiceSchema(details);
    var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    var properties = schema.putObject("properties");
    for (String field :
        List.of(
            "name",
            "code",
            "description",
            "sort",
            "enabled",
            "dictionaryCode",
            "value",
            "i18nKey")) {
      properties.putObject(field);
    }
    org.mockito.Mockito.when(
            details
                .getDialect(org.simplepoint.api.security.generator.JsonSchemaGenerator.class)
                .generateSchema(org.mockito.ArgumentMatchers.any()))
        .thenReturn(schema);
    dictionaries =
        transactional(
            new DictionaryServiceImpl(
                dictionaryRepository, details, itemRepository, boundary, registrations));
    items =
        transactional(
            new DictionaryItemServiceImpl(itemRepository, details, dictionaryRepository, boundary));
    writer =
        transactional(
            new DictionaryDeclarationWriter(boundary, dictionaries, items, registrations));
    application =
        transactional(
            new org.simplepoint.plugin.rbac.tenant.service.initialize
                .DictionaryRegistrationApplication(registrations, dictionaries, items));
    provider =
        new org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryRegistrationProvider(
            application);
  }

  @AfterEach
  void close() {
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    if (factory != null) {
      factory.close();
    }
  }

  protected DictionaryContribution declaration(String name, String value) {
    return new DictionaryContribution(
        "test",
        "seed",
        "1",
        300,
        List.of(
            new DictionaryDeclaration(
                "example",
                name,
                null,
                10,
                List.of(new DictionaryItemDeclaration(value, "Item", null, null, 10)))));
  }

  protected static DictionaryItem item(String code, String value) {
    var item = new DictionaryItem();
    item.setDictionaryCode(code);
    item.setValue(value);
    item.setName("Item");
    return item;
  }

  protected long revision() {
    return read(
        () ->
            ((Number)
                    em.createNativeQuery(
                            "select revision from simpoint_dictionary_catalog_revision")
                        .getSingleResult())
                .longValue());
  }

  protected int dictionaryCount() {
    return read(() -> em.createQuery("from Dictionary", Dictionary.class).getResultList().size());
  }

  protected int itemCount() {
    return read(
        () -> em.createQuery("from DictionaryItem", DictionaryItem.class).getResultList().size());
  }

  protected <T> T read(Supplier<T> supplier) {
    return transaction.execute(status -> supplier.get());
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
}
