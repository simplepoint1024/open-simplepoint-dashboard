/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.configuration;

import javax.sql.DataSource;
import org.simplepoint.plugin.api.PluginInstallBatchValidator;
import org.simplepoint.plugin.api.PluginInstallValidator;
import org.simplepoint.plugin.api.PluginLifecycleHandler;
import org.simplepoint.plugin.api.PluginOperationAuditRecorder;
import org.simplepoint.plugin.api.PluginRuntimeCoordinator;
import org.simplepoint.plugin.api.PluginTaskStore;
import org.simplepoint.plugin.api.PluginsManager;
import org.simplepoint.plugin.core.AbstractPluginsManager;
import org.simplepoint.plugin.core.CompositePluginArtifactVerifier;
import org.simplepoint.plugin.core.CompositePluginOperationAuditRecorder;
import org.simplepoint.plugin.core.CompositePluginRuntimeCoordinator;
import org.simplepoint.plugin.core.InMemoryPluginOperationAuditRecorder;
import org.simplepoint.plugin.core.InMemoryPluginTaskStore;
import org.simplepoint.plugin.core.MapPluginsStorage;
import org.simplepoint.plugin.core.NoopPluginRuntimeCoordinator;
import org.simplepoint.plugin.core.PluginArtifactVerifier;
import org.simplepoint.plugin.core.PluginRuntimeVersions;
import org.simplepoint.plugin.core.TrustedSha256PluginArtifactVerifier;
import org.simplepoint.plugin.core.VersionCompatibilityVerifier;
import org.simplepoint.plugin.spring.coordination.JdbcPluginOperationEventRecorder;
import org.simplepoint.plugin.spring.coordination.JdbcPluginOperationEventRelay;
import org.simplepoint.plugin.spring.coordination.JdbcPluginOperationEventStore;
import org.simplepoint.plugin.spring.coordination.JdbcPluginRuntimeCoordinator;
import org.simplepoint.plugin.spring.coordination.SpringPluginOperationEventPublisher;
import org.simplepoint.plugin.spring.handle.SpringBeanPluginInstanceHandler;
import org.simplepoint.plugin.spring.task.JdbcPluginTaskStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * Auto-configuration class for setting up the plugin manager and related components.
 * This configuration class initializes the PluginsManager and registers the plugin instance handler
 * to enable plugin management within a Spring application.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    PluginRuntimeCoordinatorProperties.class,
    PluginRuntimeEventProperties.class,
    PluginTaskStoreProperties.class,
    PluginTrustProperties.class
})
public class PluginManagerAutoConfiguration {

  private final ApplicationContext applicationContext;
  private final String coreVersion;
  private final String frontendSdkVersion;

  /**
   * Constructs a new PluginManagerAutoConfiguration instance.
   *
   * @param applicationContext the Spring ApplicationContext used to manage beans
   * @param coreVersion        backend plugin runtime version
   * @param frontendSdkVersion frontend plugin SDK version
   */
  public PluginManagerAutoConfiguration(
      ApplicationContext applicationContext,
      @Value("${plugin.runtime.core-version:}") String coreVersion,
      @Value("${plugin.runtime.frontend-sdk-version:}") String frontendSdkVersion
  ) {
    this.applicationContext = applicationContext;
    this.coreVersion = coreVersion;
    this.frontendSdkVersion = frontendSdkVersion;
  }

  /**
   * Defines a PluginsManager bean.
   * This bean is responsible for managing plugins within the application.
   *
   * @return a new PluginsManager instance
   * @throws Exception if an error occurs during the creation of the PluginsManager
   */
  @Bean
  public PluginsManager pluginsManager(
      ObjectProvider<PluginInstallBatchValidator> installBatchValidators,
      ObjectProvider<PluginInstallValidator> installValidators,
      ObjectProvider<PluginLifecycleHandler> lifecycleHandlers,
      ObjectProvider<PluginArtifactVerifier> artifactVerifiers,
      ObjectProvider<PluginOperationAuditRecorder> auditRecorders,
      ObjectProvider<PluginRuntimeCoordinator> runtimeCoordinators,
      ObjectProvider<PluginTaskStore> taskStores
  ) throws Exception {
    VersionCompatibilityVerifier verifier =
        new VersionCompatibilityVerifier(new PluginRuntimeVersions(coreVersion, frontendSdkVersion));
    PluginArtifactVerifier artifactVerifier =
        CompositePluginArtifactVerifier.of(artifactVerifiers.orderedStream().toList());
    PluginOperationAuditRecorder auditRecorder =
        CompositePluginOperationAuditRecorder.of(auditRecorders.orderedStream().toList());
    PluginRuntimeCoordinator runtimeCoordinator =
        CompositePluginRuntimeCoordinator.of(runtimeCoordinators.orderedStream().toList());
    PluginTaskStore taskStore = taskStores.orderedStream()
        .findFirst()
        .orElseGet(InMemoryPluginTaskStore::new);
    AbstractPluginsManager manager =
        new AbstractPluginsManager(
            new MapPluginsStorage(),
            verifier,
            artifactVerifier,
            auditRecorder,
            runtimeCoordinator,
            taskStore) {
        };
    installBatchValidators.orderedStream().forEach(manager::registerInstallBatchValidator);
    installValidators.orderedStream().forEach(manager::registerInstallValidator);
    lifecycleHandlers.orderedStream().forEach(manager::registerLifecycleHandler);
    return manager;
  }

  /**
   * Defines the default in-memory plugin operation audit recorder.
   *
   * @return plugin operation audit recorder
   */
  @Bean
  @ConditionalOnMissingBean(name = "pluginOperationAuditRecorder")
  public PluginOperationAuditRecorder pluginOperationAuditRecorder() {
    return new InMemoryPluginOperationAuditRecorder();
  }

  /**
   * Defines the JDBC plugin task store when explicitly enabled.
   *
   * @param dataSource data source
   * @param properties task store properties
   * @return plugin task store
   */
  @Bean
  @ConditionalOnClass(JdbcOperations.class)
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnProperty(name = "plugin.task-store.jdbc.enabled", havingValue = "true")
  @ConditionalOnMissingBean(PluginTaskStore.class)
  public PluginTaskStore jdbcPluginTaskStore(
      DataSource dataSource,
      PluginTaskStoreProperties properties
  ) {
    return new JdbcPluginTaskStore(dataSource, properties);
  }

  /**
   * Defines the default in-memory plugin task store.
   *
   * @return plugin task store
   */
  @Bean
  @ConditionalOnMissingBean(PluginTaskStore.class)
  public PluginTaskStore pluginTaskStore() {
    return new InMemoryPluginTaskStore();
  }

  /**
   * Publishes plugin operation events to Spring listeners.
   *
   * @param eventPublisher Spring application event publisher
   * @return plugin runtime coordinator
   */
  @Bean
  public PluginRuntimeCoordinator springPluginOperationEventPublisher(ApplicationEventPublisher eventPublisher) {
    return new SpringPluginOperationEventPublisher(eventPublisher);
  }

  /**
   * Defines the JDBC plugin operation event store when explicitly enabled.
   *
   * @param dataSource data source
   * @param properties runtime event properties
   * @return plugin operation event store
   */
  @Bean
  @ConditionalOnClass(JdbcOperations.class)
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnProperty(name = "plugin.runtime-events.jdbc.enabled", havingValue = "true")
  @ConditionalOnMissingBean(JdbcPluginOperationEventStore.class)
  public JdbcPluginOperationEventStore jdbcPluginOperationEventStore(
      DataSource dataSource,
      PluginRuntimeEventProperties properties
  ) {
    return new JdbcPluginOperationEventStore(dataSource, properties);
  }

  /**
   * Records local plugin operation events into the JDBC event log.
   *
   * @param eventStore JDBC plugin operation event store
   * @param properties runtime event properties
   * @return plugin runtime coordinator
   */
  @Bean
  @ConditionalOnBean(JdbcPluginOperationEventStore.class)
  @ConditionalOnProperty(name = "plugin.runtime-events.jdbc.enabled", havingValue = "true")
  public PluginRuntimeCoordinator jdbcPluginOperationEventRecorder(
      JdbcPluginOperationEventStore eventStore,
      PluginRuntimeEventProperties properties
  ) {
    return new JdbcPluginOperationEventRecorder(eventStore, properties);
  }

  /**
   * Relays JDBC plugin operation events from other nodes to the local Spring event bus.
   *
   * @param eventStore     JDBC plugin operation event store
   * @param eventPublisher Spring application event publisher
   * @param properties     runtime event properties
   * @return plugin operation event relay
   */
  @Bean
  @ConditionalOnBean(JdbcPluginOperationEventStore.class)
  @ConditionalOnProperty(name = "plugin.runtime-events.jdbc.relay-enabled", havingValue = "true", matchIfMissing = true)
  public JdbcPluginOperationEventRelay jdbcPluginOperationEventRelay(
      JdbcPluginOperationEventStore eventStore,
      ApplicationEventPublisher eventPublisher,
      PluginRuntimeEventProperties properties
  ) {
    return new JdbcPluginOperationEventRelay(eventStore, eventPublisher, properties);
  }

  /**
   * Defines the JDBC runtime coordinator when explicitly enabled.
   *
   * @param dataSource data source
   * @param properties runtime coordinator properties
   * @return plugin runtime coordinator
   */
  @Bean
  @ConditionalOnClass(JdbcOperations.class)
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnProperty(name = "plugin.runtime-coordinator.jdbc.enabled", havingValue = "true")
  public PluginRuntimeCoordinator jdbcPluginRuntimeCoordinator(
      DataSource dataSource,
      PluginRuntimeCoordinatorProperties properties
  ) {
    return new JdbcPluginRuntimeCoordinator(dataSource, properties);
  }

  /**
   * Defines the default single-node plugin runtime coordinator.
   *
   * @return plugin runtime coordinator
   */
  @Bean
  @ConditionalOnMissingBean(PluginRuntimeCoordinator.class)
  public PluginRuntimeCoordinator pluginRuntimeCoordinator() {
    return NoopPluginRuntimeCoordinator.INSTANCE;
  }

  /**
   * Defines the property-backed SHA-256 artifact verifier.
   *
   * @param properties plugin trust properties
   * @return plugin artifact verifier
   */
  @Bean
  public PluginArtifactVerifier trustedSha256PluginArtifactVerifier(PluginTrustProperties properties) {
    return new TrustedSha256PluginArtifactVerifier(properties.getSha256(), properties.isRequireKnownSha256());
  }

  /**
   * Defines a SpringBeanPluginInstanceHandler bean and registers it with the PluginsManager.
   * The handler is responsible for managing plugin instances using the Spring ApplicationContext.
   *
   * @param pluginsManager the PluginsManager used to manage plugin handlers
   * @return a new SpringBeanPluginInstanceHandler instance
   */
  @Bean
  public SpringBeanPluginInstanceHandler pluginInstanceHandler(PluginsManager pluginsManager) {
    SpringBeanPluginInstanceHandler handler =
        new SpringBeanPluginInstanceHandler(this.applicationContext, pluginsManager);
    pluginsManager.registerHandle(handler);
    return handler;
  }
}
