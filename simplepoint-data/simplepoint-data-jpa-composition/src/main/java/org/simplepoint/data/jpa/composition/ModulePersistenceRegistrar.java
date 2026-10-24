package org.simplepoint.data.jpa.composition;

import java.util.ArrayList;
import java.util.HashSet;
import javax.sql.DataSource;
import org.hibernate.cfg.ManagedBeanSettings;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.hibernate.autoconfigure.HibernateSettings;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.JpaRepositoryConfigExtension;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.config.AnnotationRepositoryConfigurationSource;
import org.springframework.data.repository.config.RepositoryConfigurationDelegate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Uses Spring Data's repository registrar, preserving custom repository/fragment semantics. */
final class ModulePersistenceRegistrar implements BeanDefinitionRegistryPostProcessor {
  private final ModulePlan modules;
  private final Environment environment;
  private final ResourceLoader loader;
  private final ModulePersistencePlan plan;

  ModulePersistenceRegistrar(ModulePlan modules, Environment environment, ResourceLoader loader) {
    this.modules = modules;
    this.environment = environment;
    this.loader = loader;
    this.plan = ModulePersistencePlan.discover(modules, environment, loader);
  }

  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
      throws BeansException {
    register(
        registry,
        ModulePersistencePlan.class.getName(),
        BeanDefinitionBuilder.genericBeanDefinition(ModulePersistencePlan.class, () -> plan));
    register(
        registry,
        ModuleStorageBindings.class.getName(),
        BeanDefinitionBuilder.genericBeanDefinition(ModuleStorageBindings.class, plan::bindings));
    if (registry.containsBeanDefinition(
        "org.springframework.boot.persistence.autoconfigure.EntityScanPackages")) {
      throw new ModuleCompositionException("GLOBAL_ENTITY_SCAN_WITH_MODULES", "");
    }
    for (String name : registry.getBeanDefinitionNames()) {
      var definition = registry.getBeanDefinition(name);
      if (JpaRepositoryFactoryBean.class.getName().equals(definition.getBeanClassName())) {
        throw new ModuleCompositionException("GLOBAL_REPOSITORY_SCAN_WITH_MODULES", name);
      }
    }
    String original = ModuleTransactionAttributes.class.getName() + ".original";
    if (registry.containsBeanDefinition(original)) {
      throw new ModuleCompositionException("DUPLICATE_MODULE_PERSISTENCE", "");
    }
    String transactions = "transactionAttributeSource";
    if (!registry.containsBeanDefinition(transactions)) {
      throw new ModuleCompositionException("MISSING_TRANSACTION_ATTRIBUTE_SOURCE", "");
    }
    var definition = registry.getBeanDefinition(transactions);
    registry.removeBeanDefinition(transactions);
    definition.setAutowireCandidate(false);
    registry.registerBeanDefinition(original, definition);
    register(
        registry,
        transactions,
        BeanDefinitionBuilder.genericBeanDefinition(ModuleTransactionAttributes.class)
            .addConstructorArgReference(original)
            .addConstructorArgValue(modules)
            .addConstructorArgValue(plan.bindings())
            .setRole(BeanDefinition.ROLE_INFRASTRUCTURE));
    for (var store : plan.stores().values()) {
      var binding = store.binding();
      var factory =
          BeanDefinitionBuilder.genericBeanDefinition(ModuleEntityManagerFactory.class)
              .addConstructorArgValue(store)
              .addConstructorArgReference(binding.dataSource())
              .addConstructorArgValue(environment)
              .setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
      // Type discovery must not instantiate this factory before property binding is installed.
      factory.getBeanDefinition().setAttribute(
          FactoryBean.OBJECT_TYPE_ATTRIBUTE, jakarta.persistence.EntityManagerFactory.class);
      register(
          registry,
          binding.entityManagerFactory(),
          factory);
      register(
          registry,
          binding.entityManager(),
          BeanDefinitionBuilder.genericBeanDefinition(SharedEntityManagerCreator.class)
              .setFactoryMethod("createSharedEntityManager")
              .addConstructorArgReference(binding.entityManagerFactory()));
      register(
          registry,
          binding.transactionManager(),
          BeanDefinitionBuilder.genericBeanDefinition(JpaTransactionManager.class)
              .addConstructorArgReference(binding.entityManagerFactory()));
      register(
          registry,
          binding.jdbcTemplate(),
          BeanDefinitionBuilder.genericBeanDefinition(JdbcTemplate.class)
              .addConstructorArgReference(binding.dataSource()));
      var source =
          new AnnotationRepositoryConfigurationSource(
              AnnotationMetadata.introspect(Repositories.class),
              EnableJpaRepositories.class,
              loader,
              environment,
              registry,
              null);
      source
          .getAttributes()
          .put(
              "basePackages",
              modules.modules().stream()
                  .filter(module -> store.modules().contains(module.id()))
                  .flatMap(module -> module.packages().stream())
                  .toArray(String[]::new));
      source.getAttributes().put("entityManagerFactoryRef", binding.entityManagerFactory());
      source.getAttributes().put("transactionManagerRef", binding.transactionManager());
      new RepositoryConfigurationDelegate(source, loader, environment)
          .registerRepositoriesIn(registry, new JpaRepositoryConfigExtension());
    }
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    if (!(beanFactory instanceof DefaultListableBeanFactory beans)) {
      throw new ModuleCompositionException("UNSUPPORTED_PERSISTENCE_CONTAINER", "");
    }
    var sources = new HashSet<String>();
    for (var store : plan.stores().values()) {
      if (!sources.add(beans.canonicalName(store.binding().dataSource()))) {
        throw new ModuleCompositionException(
            "DUPLICATE_STORAGE_DATASOURCE", store.binding().store());
      }
    }
    beans.setAutowireCandidateResolver(
        new ModulePersistenceCandidates(
            beans.getAutowireCandidateResolver(), modules, plan.bindings()));
  }

  private static void register(
      BeanDefinitionRegistry registry, String name, BeanDefinitionBuilder builder) {
    if (registry.containsBeanDefinition(name) || registry.isAlias(name)) {
      throw new ModuleCompositionException("CONFLICTING_PERSISTENCE_BEAN", name);
    }
    registry.registerBeanDefinition(name, builder.getBeanDefinition());
  }

  @EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)
  private static class Repositories {}

  /** Standard Hibernate factory with Boot naming/properties and explicit managed classes. */
  public static class ModuleEntityManagerFactory extends LocalContainerEntityManagerFactoryBean {
    /** Receives the actual named datasource, never an injection-selected domain resource. */
    public ModuleEntityManagerFactory(
        ModulePersistencePlan.Store store,
        DataSource source,
        Environment environment,
        ConfigurableListableBeanFactory beans) {
      var properties =
          Binder.get(environment)
              .bind("spring.jpa", JpaProperties.class)
              .orElseGet(JpaProperties::new);
      final var hibernate =
          Binder.get(environment)
              .bind("spring.jpa.hibernate", HibernateProperties.class)
              .orElseGet(HibernateProperties::new);
      var adapter = new HibernateJpaVendorAdapter();
      adapter.setShowSql(properties.isShowSql());
      if (properties.getDatabasePlatform() != null) {
        adapter.setDatabasePlatform(properties.getDatabasePlatform());
      }
      setJpaVendorAdapter(adapter);
      var customizers = new ArrayList<HibernatePropertiesCustomizer>();
      customizers.add(
          values -> values.put(ManagedBeanSettings.BEAN_CONTAINER, new SpringBeanContainer(beans)));
      customizers.addAll(
          beans.getBeanProvider(HibernatePropertiesCustomizer.class).orderedStream().toList());
      setJpaPropertyMap(
          hibernate.determineHibernateProperties(
              properties.getProperties(),
              new HibernateSettings()
                  .ddlAuto(() -> "validate")
                  .hibernatePropertiesCustomizers(customizers)));
      setDataSource(source);
      setPersistenceUnitName("simplepoint-" + store.binding().store());
      setManagedTypes(PersistenceManagedTypes.of(store.managedTypes(), store.managedPackages()));
    }
  }
}
