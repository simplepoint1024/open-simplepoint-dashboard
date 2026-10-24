package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleDescriptor;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PackagedPersistenceTest {
  private final ModuleCatalog modules = ModuleCatalog.discover(getClass().getClassLoader());

  @Test
  void actualDomainPersistenceIsOwnedAndOnlySelectedModulesAreScanned() {
    var all = modules.plan(modules.modules().stream().map(ModuleDescriptor::id).toList());
    var plan =
        ModulePersistencePlan.discover(all, new StandardEnvironment(), new DefaultResourceLoader());
    assertEquals(1, plan.stores().size());
    assertEquals("identity", plan.entityOwners().get("org.simplepoint.security.entity.User"));
    assertEquals(
        "app-form",
        plan.entityOwners().get("org.simplepoint.plugin.app.form.entity.WorkspaceApplication"));
    assertEquals(
        "app-form",
        plan.entityOwners().get("org.simplepoint.plugin.app.form.entity.WorkspaceApplicationPage"));
    assertEquals(
        "app-form",
        plan.entityOwners()
            .get("org.simplepoint.plugin.app.form.entity.WorkspaceApplicationReadModel"));
    assertEquals(
        "app-form",
        plan.entityOwners().get("org.simplepoint.app.form.entity.ApplicationFormReleaseReadModel"));
    assertEquals(
        "app-form",
        plan.entityOwners()
            .get("org.simplepoint.app.form.entity.ApplicationFormSubmissionReadModel"));
    assertEquals(
        "dna-federation",
        plan.entityOwners().get("org.simplepoint.plugin.dna.federation.entity.DnaDataModel"));
    assertTrue(plan.entityOwners().size() > 100);
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
    var unowned =
        scanner.findCandidateComponents("org.simplepoint").stream()
            .map(BeanDefinition::getBeanClassName)
            .filter(type -> !plan.entityOwners().containsKey(type))
            .toList();
    assertEquals(List.of(), unowned, "Every domain entity requires a unique module owner");
    var dna =
        ModulePersistencePlan.discover(
            modules.plan(List.of("dna-runtime")),
            new StandardEnvironment(),
            new DefaultResourceLoader());
    assertFalse(dna.entityOwners().containsKey("org.simplepoint.security.entity.User"));
    assertTrue(dna.entityOwners().values().stream().allMatch(owner -> owner.startsWith("dna-")));
  }

  @Test
  void actualRepositoriesAndMappingsStartTogetherInOnePersistenceUnit() {
    assertActualPersistence(Map.of());
  }

  @Test
  void actualRepositoriesAndMappingsCanUseIndependentStorageGroups() {
    assertActualPersistence(
        Map.of(
            "simplepoint.persistence.modules.identity", "security",
            "simplepoint.persistence.sources.security", "securitySource",
            "simplepoint.persistence.modules.dna-core", "dna",
            "simplepoint.persistence.modules.dna-runtime", "dna",
            "simplepoint.persistence.sources.dna", "dnaSource"));
  }

  private void assertActualPersistence(Map<String, Object> assignments) {
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "isolated-test",
                  Map.of(
                      "spring.jpa.hibernate.ddl-auto", "create-drop",
                      "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error", "true")));
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("assignments", assignments));
      context.registerBean(
          ModulePlan.class,
          () -> modules.plan(modules.modules().stream().map(ModuleDescriptor::id).toList()));
      for (String name : List.of("dataSource", "securitySource", "dnaSource")) {
        context.registerBean(
            name,
            DataSource.class,
            () ->
                new DriverManagerDataSource(
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                    "sa",
                    ""));
      }
      context.register(OwnedPersistence.class);
      context.refresh();
      var plan = context.getBean(ModulePersistencePlan.class);
      assertEquals(assignments.isEmpty() ? 1 : 3, plan.stores().size());
      for (var store : plan.stores().values()) {
        var factory =
            context.getBean(store.binding().entityManagerFactory(), EntityManagerFactory.class);
        assertTrue(
            factory.getMetamodel().getEntities().stream()
                .allMatch(
                    entity ->
                        store
                            .modules()
                            .contains(plan.entityOwners().get(entity.getJavaType().getName()))));
      }
      var repositories = context.getBeanNamesForType(Repository.class);
      assertTrue(repositories.length > 0);
      for (String repository : repositories) {
        var information =
            context
                .getBean("&" + repository, RepositoryFactoryInformation.class)
                .getRepositoryInformation();
        String owner = plan.entityOwners().get(information.getDomainType().getName());
        var factory =
            context.getBean(
                plan.bindings().require(owner).entityManagerFactory(), EntityManagerFactory.class);
        assertTrue(
            factory.getMetamodel().getEntities().stream()
                .anyMatch(entity -> entity.getJavaType().equals(information.getDomainType())));
        if (context.getBean(repository) instanceof CrudRepository<?, ?> crud) {
          assertEquals(0, crud.count());
        }
      }
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class OwnedPersistence {}

  @Test
  void everyActualModuleDeclaresItsMigrationOwnership() {
    var selected = modules.plan(modules.modules().stream().map(ModuleDescriptor::id).toList());
    var storage =
        ModulePersistencePlan.discover(
            selected, new StandardEnvironment(), new DefaultResourceLoader());
    var migrations =
        org.simplepoint.data.migration.ModuleMigrationPlan.discover(selected, storage.bindings());
    assertEquals(
        List.of(
            "ai-catalog",
            "ai-core",
            "ai-knowledge",
            "ai-mcp",
            "ai-runtime",
            "app-form",
            "audit-logging",
            "audit-rate-limit",
            "bootstrap-registry",
            "dna-core",
            "dna-federation",
            "dna-runtime",
            "flowable-process",
            "i18n",
            "identity",
            "notification",
            "oidc",
            "personal",
            "platform-events",
            "storage",
            "workflow"),
        migrations.schemas().stream()
            .map(org.simplepoint.data.migration.ModuleMigrationPlan.ModuleSchema::module)
            .sorted()
            .toList());
    assertEquals(List.of(), migrations.undeclaredModules());
    assertEquals(
        "MISSING_MIGRATION_DATASOURCE",
        assertThrows(
                org.simplepoint.data.migration.ModuleMigrationException.class,
                () ->
                    new org.simplepoint.data.migration.ModuleMigrationEngine()
                        .migrate(migrations, Map.of()))
            .code());
  }

  @Test
  void storageInheritsIdentityStoreAndConflictingAssignmentsAreRejected() {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "selection",
                Map.of(
                    "simplepoint.persistence.modules.identity", "security",
                    "simplepoint.persistence.sources.security", "securitySource")));
    var selection = modules.plan(List.of("storage"));
    var plan = ModulePersistencePlan.discover(selection, environment, new DefaultResourceLoader());
    assertEquals(plan.bindings().require("identity"), plan.bindings().require("storage"));
    assertEquals("securitySource", plan.bindings().require("storage").dataSource());
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "conflict", Map.of("simplepoint.persistence.modules.storage", "other")));
    assertTrue(
        assertThrows(
                ModuleCompositionException.class,
                () ->
                    ModulePersistencePlan.discover(
                        selection, environment, new DefaultResourceLoader()))
            .getMessage()
            .contains("INCOMPATIBLE_MODULE_STORES"));
  }
}
