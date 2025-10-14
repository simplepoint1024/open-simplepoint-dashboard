/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.rpc;

import cn.hutool.core.collection.ConcurrentHashSet;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.simplepoint.core.ApplicationContextHolder;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.data.amqp.rpc.annotation.EnableAmqpRemoteClients;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * A registrar for dynamically registering AMQP (Advanced Message Queuing Protocol) remote client beans.
 * This class scans specified packages for interfaces annotated with {@link AmqpRemoteClient}
 * and registers proxy implementations for them in the Spring context.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RemoteClientsRegistrar
    implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware, Ordered {

  /**
   * The Spring Environment object used for property resolution and environment-specific configurations.
   */
  private Environment environment;

  /**
   * The ResourceLoader used for scanning resources during component discovery.
   */
  private ResourceLoader resourceLoader;

  /**
   * Sets the environment instance.
   *
   * @param environment the Spring {@link Environment} object
   */
  @Override
  public void setEnvironment(@NotNull Environment environment) {
    this.environment = environment;
  }

  /**
   * Sets the resource loader instance.
   *
   * @param resourceLoader the Spring {@link ResourceLoader} object
   */
  @Override
  public void setResourceLoader(@NotNull ResourceLoader resourceLoader) {
    this.resourceLoader = resourceLoader;
  }

  /**
   * Registers bean definitions for remote clients by scanning for annotated interfaces
   * in the specified packages.
   *
   * @param metadata the annotation metadata of the importing class
   * @param registry the Spring {@link BeanDefinitionRegistry} to register beans
   */
  @Override
  public void registerBeanDefinitions(@NotNull AnnotationMetadata metadata,
                                      @NotNull BeanDefinitionRegistry registry) {
    final var candidateComponents = new ConcurrentHashSet<BeanDefinition>();
    ClassPathScanningCandidateComponentProvider scanner = this.getScanner();

    scanner.setResourceLoader(this.resourceLoader);
    scanner.setEnvironment(this.environment);
    scanner.addIncludeFilter(new AnnotationTypeFilter(AmqpRemoteClient.class));

    // Scan the base packages for candidates
    getBasePackages(metadata).forEach(basePackage ->
        candidateComponents.addAll(scanner.findCandidateComponents(basePackage)));

    // Register bean definitions for each candidate component
    candidateComponents.forEach(candidateComponent -> {
      if (candidateComponent instanceof AnnotatedBeanDefinition beanDefinition) {
        AnnotationMetadata annotationMetadata = beanDefinition.getMetadata();
        Assert.isTrue(annotationMetadata.isInterface(),
            "@RemoteClient can only be specified on an interface");

        Map<String, Object> attributes =
            annotationMetadata.getAnnotationAttributes(AmqpRemoteClient.class.getCanonicalName());
        String className = annotationMetadata.getClassName();
        try {
          this.registerBeanDefinition(registry, className, attributes);
        } catch (NoSuchBeanDefinitionException e) {
          log.info("Ignoring bean creation due to exception: ", e);
        } catch (ClassNotFoundException e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  /**
   * Registers a proxy bean definition for a remote client.
   *
   * @param registry   the {@link BeanDefinitionRegistry} where the bean is registered
   * @param className  the fully qualified name of the client interface
   * @param attributes the attributes from the {@link AmqpRemoteClient} annotation
   * @throws ClassNotFoundException if the client interface class cannot be found
   */
  protected void registerBeanDefinition(BeanDefinitionRegistry registry, String className,
                                        Map<String, Object> attributes)
      throws ClassNotFoundException {
    Class<?> clazz = ClassUtils.forName(className, ApplicationContextHolder.getClassloader());
    registry.registerBeanDefinition(className,
        RemoteProxyFactory.proxy(this.getClass().getClassLoader(), clazz, attributes));
  }

  /**
   * Configures the scanner for discovering candidate components.
   *
   * @return an instance of {@link ClassPathScanningCandidateComponentProvider}
   */
  protected ClassPathScanningCandidateComponentProvider getScanner() {
    return new ClassPathScanningCandidateComponentProvider(false, this.environment) {
      @Override
      protected boolean isCandidateComponent(@NotNull AnnotatedBeanDefinition beanDefinition) {
        return beanDefinition.getMetadata().isIndependent()
            && !beanDefinition.getMetadata().isAnnotation();
      }
    };
  }

  /**
   * Retrieves the base packages to scan for annotated interfaces.
   *
   * @param importingClassMetadata the metadata of the importing class
   * @return a set of base packages to scan
   */
  protected Set<String> getBasePackages(AnnotationMetadata importingClassMetadata) {
    Map<String, Object> attributes = importingClassMetadata.getAnnotationAttributes(
        EnableAmqpRemoteClients.class.getCanonicalName());
    Set<String> basePackages = new HashSet<>();
    if (attributes == null) {
      return basePackages;
    }

    // Add packages from "value" and "basePackages"
    Arrays.stream((String[]) attributes.get("value"))
        .filter(StringUtils::hasText)
        .forEach(basePackages::add);

    Arrays.stream((String[]) attributes.get("basePackages"))
        .filter(StringUtils::hasText)
        .forEach(basePackages::add);

    // Add packages from "basePackageClasses"
    Arrays.stream((Class<?>[]) attributes.get("basePackageClasses"))
        .map(ClassUtils::getPackageName)
        .forEach(basePackages::add);

    // Default to the importing class's package if none are specified
    if (basePackages.isEmpty()) {
      basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
    }

    return basePackages;
  }

  /**
   * Specifies the order of this registrar when multiple registrars are present.
   *
   * @return the order value
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
