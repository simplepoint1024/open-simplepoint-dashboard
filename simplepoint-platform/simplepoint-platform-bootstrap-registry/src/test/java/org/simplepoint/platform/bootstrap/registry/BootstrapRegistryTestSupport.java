package org.simplepoint.platform.bootstrap.registry;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.simplepoint.platform.bootstrap.entity.PlatformContributionRecord;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfers;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapRegistryWriteBoundary;
import org.simplepoint.platform.bootstrap.registry.repository.PlatformContributionRepository;
import org.simplepoint.platform.bootstrap.registry.service.impl.PlatformContributionServiceImpl;
import org.simplepoint.platform.bootstrap.service.PlatformContributionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual repositories and transactions; optional PostgreSQL uses a new owned schema per test. */
abstract class BootstrapRegistryTestSupport {
  protected AnnotationConfigApplicationContext context;
  protected PlatformContributionService registry;
  protected PlatformContributionRepository repository;
  protected BootstrapLegacyTransfers transfers;
  protected JdbcTemplate jdbc;
  protected TransactionTemplate transaction;
  private String schema;

  @BeforeEach
  void open() {
    context = new AnnotationConfigApplicationContext(Database.class);
    registry = context.getBean(PlatformContributionService.class);
    repository = context.getBean(PlatformContributionRepository.class);
    transfers = context.getBean(BootstrapLegacyTransfers.class);
    jdbc = new JdbcTemplate(context.getBean(DataSource.class));
    transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    schema = context.getBean(Database.class).schema;
  }

  @AfterEach
  void close() {
    if (context != null) {
      context.close();
    }
    if (schema != null && jdbc != null) {
      jdbc.execute("drop schema " + schema + " cascade");
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  @EnableJpaRepositories(basePackageClasses = PlatformContributionRepository.class)
  static class Database {
    private String schema;

    @Bean
    DataSource dataSource() {
      String url = System.getenv("SP_BOOTSTRAP_TEST_POSTGRES_URL");
      DriverManagerDataSource source;
      if (url == null) {
        source =
            new DriverManagerDataSource(
                "jdbc:h2:mem:bootstrap-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      } else {
        if (!url.equals("jdbc:postgresql://127.0.0.1:45436/bootstrap_test")) {
          throw new IllegalArgumentException(
              "Only the disposable bootstrap test database is allowed");
        }
        schema = "bootstrap_" + UUID.randomUUID().toString().replace("-", "");
        var base =
            new DriverManagerDataSource(
                url, "resource_test", System.getenv("SP_BOOTSTRAP_TEST_POSTGRES_PASSWORD"));
        new JdbcTemplate(base).execute("create schema " + schema);
        source =
            new DriverManagerDataSource(
                url + "?currentSchema=" + schema, base.getUsername(), base.getPassword());
      }
      new ResourceDatabasePopulator(
              new ClassPathResource(
                  "META-INF/simplepoint/modules/bootstrap-registry/db/postgresql/V1__bootstrap_registry_foundation.sql"),
              new ClassPathResource(
                  "META-INF/simplepoint/modules/bootstrap-registry/db/postgresql/V2__legacy_contribution_transfers.sql"))
          .execute(source);
      return source;
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(source);
      factory.setPackagesToScan(PlatformContributionRecord.class.getPackageName());
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(
          Map.of(
              "hibernate.hbm2ddl.auto",
              "validate",
              "hibernate.physical_naming_strategy",
              "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
      return factory;
    }

    @Bean
    @org.springframework.context.annotation.Primary
    EntityManager entityManager(EntityManagerFactory factory) {
      return SharedEntityManagerCreator.createSharedEntityManager(factory);
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    BootstrapRegistryWriteBoundary boundary(EntityManager entityManager) {
      return new BootstrapRegistryWriteBoundary(entityManager);
    }

    @Bean
    BootstrapLegacyTransfers transfers(
        EntityManager entityManager, BootstrapRegistryWriteBoundary boundary) {
      return new BootstrapLegacyTransfers(entityManager, boundary);
    }

    @Bean
    PlatformContributionService registry(
        PlatformContributionRepository repository,
        BootstrapRegistryWriteBoundary boundary,
        BootstrapLegacyTransfers transfers) {
      return new PlatformContributionServiceImpl(repository, boundary, transfers);
    }
  }
}
