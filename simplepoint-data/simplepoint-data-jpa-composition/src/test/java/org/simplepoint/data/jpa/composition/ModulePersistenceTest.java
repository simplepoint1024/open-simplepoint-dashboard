package org.simplepoint.data.jpa.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.EventRetryPolicy;
import org.simplepoint.events.jdbc.JdbcEventStore;
import org.simplepoint.persistencefixture.alpha.AlphaModule;
import org.simplepoint.persistencefixture.alpha.AlphaRecord;
import org.simplepoint.persistencefixture.alpha.AlphaRepository;
import org.simplepoint.persistencefixture.alpha.AlphaService;
import org.simplepoint.persistencefixture.beta.BetaModule;
import org.simplepoint.persistencefixture.beta.BetaRepository;
import org.simplepoint.persistencefixture.beta.BetaService;
import org.simplepoint.persistencefixture.beta.ForeignTransactions;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

class ModulePersistenceTest {
  private ApplicationContextRunner runner(String... selected) {
    var plan =
        new ModuleCatalog(List.of(AlphaModule.class, BetaModule.class)).plan(List.of(selected));
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(Persistence.class)
            .withPropertyValues("spring.jpa.hibernate.ddl-auto=create-drop")
            .withBean(ModulePlan.class, () -> plan)
            .withBean(
                "dataSource",
                DataSource.class,
                () -> source("alpha"),
                definition -> definition.setPrimary(true));
    if (List.of(selected).contains("alpha")) {
      runner = runner.withBean(AlphaService.class);
    }
    if (List.of(selected).contains("beta")) {
      runner = runner.withBean(BetaService.class);
    }
    return runner;
  }

  @Test
  void defaultSharedStoreUsesSelectedRepositoriesAndInheritedPlatformMappings() {
    runner("alpha")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var service = context.getBean(AlphaService.class);
              String id = service.create("one", false);
              assertThat(service.count()).isEqualTo(1);
              assertThat(context.getBean(AlphaRepository.class).findById(id)).isPresent();
              var plan = context.getBean(ModulePersistencePlan.class);
              assertThat(plan.entityOwners()).containsEntry(AlphaRecord.class.getName(), "alpha");
              assertThat(plan.entityOwners().keySet()).noneMatch(name -> name.contains(".beta."));
              assertThat(context.getBeansOfType(BetaRepository.class)).isEmpty();
              assertThatThrownBy(() -> service.create("rollback", true))
                  .isInstanceOf(IllegalStateException.class);
              assertThat(service.count()).isEqualTo(1);
            });
  }

  @Test
  void sharedStorePreservesAnExplicitLocalTransactionAcrossBothRepositories() {
    runner("alpha", "beta")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var alpha = context.getBean(AlphaService.class);
              var beta = context.getBean(BetaService.class);
              assertThatThrownBy(
                      () ->
                          alpha.execute(
                              () -> {
                                beta.create("joined", false);
                                throw new IllegalStateException("rollback both");
                              }))
                  .isInstanceOf(IllegalStateException.class);
              assertThat(beta.count()).isZero();
              assertThat(context.getBean(ModulePersistencePlan.class).stores()).hasSize(1);
            });
  }

  @Test
  void separateStoresBindRepositoriesEntityManagerJdbcAndTransactionsToTheirOwners() {
    runner("alpha", "beta")
        .withPropertyValues(
            "simplepoint.persistence.modules.beta=second",
            "simplepoint.persistence.sources.second=betaSource")
        .withBean("betaSource", DataSource.class, () -> source("beta"))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var alpha = context.getBean(AlphaService.class);
              var beta = context.getBean(BetaService.class);
              alpha.create("alpha", false);
              beta.create("beta", false);
              assertThat(alpha.count()).isEqualTo(1);
              assertThat(beta.count()).isEqualTo(1);
              assertThatThrownBy(
                      () ->
                          alpha.execute(
                              () -> {
                                beta.create("independent", false);
                                throw new IllegalStateException("only alpha rolls back");
                              }))
                  .isInstanceOf(IllegalStateException.class);
              assertThat(beta.count()).isEqualTo(2);
              assertThat(beta.jdbcCount()).isEqualTo(2);
              assertThat(beta.dataSource()).isSameAs(context.getBean("betaSource"));
              assertThat(beta.readOnly()).isTrue();
              assertThatThrownBy(beta::checkedRollback).hasMessage("checked failure");
              assertThat(beta.count()).isEqualTo(2);
              var bindings = context.getBean(ModuleStorageBindings.class);
              assertThat(beta.transactionManager())
                  .isSameAs(context.getBean(bindings.require("beta").transactionManager()));
              var alphaJdbc =
                  context.getBean(bindings.require("alpha").jdbcTemplate(), JdbcTemplate.class);
              var betaJdbc =
                  context.getBean(bindings.require("beta").jdbcTemplate(), JdbcTemplate.class);
              assertThat(alphaJdbc.queryForObject("select count(*) from alpha_records", Long.class))
                  .isEqualTo(1);
              assertThat(betaJdbc.queryForObject("select count(*) from beta_records", Long.class))
                  .isEqualTo(2);
              assertThatThrownBy(
                      () ->
                          alphaJdbc.queryForObject("select count(*) from beta_records", Long.class))
                  .isInstanceOf(DataAccessException.class);
            });
  }

  @Test
  void jpaJdbcAndOutboxShareTheBoundBusinessTransaction() {
    runner("alpha", "beta")
        .withPropertyValues(
            "simplepoint.persistence.modules.beta=second",
            "simplepoint.persistence.sources.second=betaSource")
        .withBean("betaSource", DataSource.class, () -> source("beta"))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var binding = context.getBean(ModuleStorageBindings.class).require("beta");
              var source = context.getBean(binding.dataSource(), DataSource.class);
              var manager =
                  context.getBean(binding.transactionManager(), PlatformTransactionManager.class);
              new ResourceDatabasePopulator(
                      new ClassPathResource("META-INF/simplepoint/events/schema-v1.sql"))
                  .execute(source);
              var jdbc = context.getBean(binding.jdbcTemplate(), JdbcTemplate.class);
              jdbc.execute("create table direct_effects (id integer)");
              var store =
                  new JdbcEventStore(
                      source,
                      manager,
                      new EventCodec(),
                      "beta",
                      Clock.systemUTC(),
                      new EventRetryPolicy(
                          3, Duration.ofSeconds(10), Duration.ofMillis(10), Duration.ofSeconds(1)));
              var service = context.getBean(BetaService.class);
              Runnable write =
                  () -> {
                    service.create("transaction", false);
                    jdbc.update("insert into direct_effects values (1)");
                    store.publish(
                        new EventMessage(
                            UUID.randomUUID(),
                            "fixture.changed",
                            1,
                            "beta",
                            "tenant",
                            "resource",
                            1,
                            "operation",
                            "correlation",
                            Instant.now(),
                            JsonNodeFactory.instance.objectNode()));
                  };
              assertThatThrownBy(
                      () ->
                          service.execute(
                              () -> {
                                write.run();
                                throw new IllegalStateException("rollback all");
                              }))
                  .isInstanceOf(IllegalStateException.class);
              assertThat(service.count()).isZero();
              assertThat(jdbc.queryForObject("select count(*) from direct_effects", Integer.class))
                  .isZero();
              assertThat(store.count("OUTBOX", "PENDING")).isZero();
              service.execute(write);
              assertThat(service.count()).isEqualTo(1);
              assertThat(jdbc.queryForObject("select count(*) from direct_effects", Integer.class))
                  .isEqualTo(1);
              assertThat(store.count("OUTBOX", "PENDING")).isEqualTo(1);
            });
  }

  @Test
  void ordinaryBootDatasourceAutoConfigurationWorksWithModuleSelection() {
    String url = "jdbc:h2:mem:" + UUID.randomUUID();
    new ApplicationContextRunner()
        .withUserConfiguration(AutoConfigured.class)
        .withPropertyValues(
            "spring.datasource.url=" + url,
            "spring.datasource.username=module_runtime",
            "spring.jpa.hibernate.ddl-auto=create-drop")
        .withBean(
            ModulePlan.class,
            () -> new ModuleCatalog(List.of(AlphaModule.class)).plan(List.of("alpha")))
        .withBean(AlphaService.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
              var properties = context.getBean(
                  org.springframework.boot.jdbc.autoconfigure.DataSourceProperties.class);
              assertThat(properties.getUrl()).isEqualTo(url);
              assertThat(properties.getUsername()).isEqualTo("module_runtime");
              context.getBean(AlphaService.class).create("auto-configured", false);
              assertThat(context.getBean(AlphaService.class).count()).isEqualTo(1);
            });
  }

  @Test
  void unknownModuleBindingAndDuplicateStoreDatasourceFailBeforeUse() {
    runner("alpha")
        .withPropertyValues("simplepoint.persistence.modules.missing=other")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("UNKNOWN_PERSISTENCE_MODULE"));
    runner("alpha", "beta")
        .withPropertyValues(
            "simplepoint.persistence.modules.beta=second",
            "simplepoint.persistence.sources.second=dataSource")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("DUPLICATE_STORAGE_DATASOURCE"));
  }

  @Test
  void moduleDiscoveryAndPersistenceCanAssembleTheSameHostWithoutGlobalBusinessScanning() {
    new ApplicationContextRunner()
        .withUserConfiguration(Modular.class)
        .withPropertyValues(
            "simplepoint.service-router.internal-auth.mode=oauth2",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "simplepoint.persistence.modules.beta=second",
            "simplepoint.persistence.sources.second=betaSource")
        .withBean(
            "dataSource",
            DataSource.class,
            () -> source("auto-alpha"),
            definition -> definition.setPrimary(true))
        .withBean("betaSource", DataSource.class, () -> source("auto-beta"))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var alpha = context.getBean(AlphaService.class);
              var beta = context.getBean(BetaService.class);
              alpha.create("discovered-alpha", false);
              beta.create("discovered-beta", false);
              assertThat(alpha.count()).isEqualTo(1);
              assertThat(beta.jdbcCount()).isEqualTo(1);
              assertThat(beta.dataSource()).isSameAs(context.getBean("betaSource"));
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @EnableModulePersistence
  @org.simplepoint.platform.composition.spring.EnablePlatformModules({"alpha", "beta"})
  static class Modular {}

  @Test
  void defaultStartupValidatesExistingSchemaWithoutCreatingBusinessTables() {
    var source = source("no-mutation");
    new ApplicationContextRunner()
        .withUserConfiguration(Persistence.class)
        .withBean(
            ModulePlan.class,
            () -> new ModuleCatalog(List.of(AlphaModule.class)).plan(List.of("alpha")))
        .withBean("dataSource", DataSource.class, () -> source)
        .run(
            context -> {
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(
                      org.hibernate.tool.schema.spi.SchemaManagementException.class);
              assertThat(
                      new JdbcTemplate(source)
                          .queryForObject(
                              "select count(*) from information_schema.tables where"
                                  + " table_name='ALPHA_RECORDS'",
                              Integer.class))
                  .isZero();
            });
  }

  @Test
  void legacyGlobalScansAndForeignTransactionQualifiersFailClosed() {
    runner("alpha")
        .withUserConfiguration(LegacyEntities.class)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("GLOBAL_ENTITY_SCAN_WITH_MODULES"));
    runner("alpha")
        .withUserConfiguration(LegacyRepositories.class)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("GLOBAL_REPOSITORY_SCAN_WITH_MODULES"));
    runner("beta")
        .withBean(ForeignTransactions.class)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("FOREIGN_MODULE_TRANSACTION_MANAGER"));
  }

  @Configuration(proxyBeanMethods = false)
  @EntityScan(basePackageClasses = AlphaRecord.class)
  static class LegacyEntities {}

  @Configuration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackageClasses = AlphaRepository.class)
  static class LegacyRepositories {}

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @EnableModulePersistence
  static class AutoConfigured {}

  private static DataSource source(String name) {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:" + name + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
