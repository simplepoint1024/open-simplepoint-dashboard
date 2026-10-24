package com.simplepoint.service.router.config;

import com.simplepoint.service.router.annotation.RoutedService;
import java.util.Objects;
import org.simplepoint.remoting.RemoteContract;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Registers one primary routing boundary per contract, including contracts with local providers.
 */
public class ServiceRouterProxyBeanDefinitionRegistryPostProcessor
    implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, ResourceLoaderAware, Ordered {

  private final String[] basePackages;

  private Environment environment;

  private ResourceLoader resourceLoader;

  /**
   * Creates a proxy bean definition registrar.
   *
   * @param basePackages packages to scan
   */
  public ServiceRouterProxyBeanDefinitionRegistryPostProcessor(final String[] basePackages) {
    this.basePackages = basePackages.clone();
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void setEnvironment(final Environment environment) {
    this.environment = environment;
  }

  @Override
  public void setResourceLoader(final ResourceLoader resourceLoader) {
    this.resourceLoader = resourceLoader;
  }

  @Override
  public void postProcessBeanDefinitionRegistry(final BeanDefinitionRegistry registry) throws BeansException {
    ClassPathScanningCandidateComponentProvider scanner = new InterfaceScanner(environment);
    scanner.setResourceLoader(resourceLoader);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RoutedService.class));
    scanner.addIncludeFilter(new AnnotationTypeFilter(RemoteContract.class));
    for (String basePackage : basePackages) {
      scanner.findCandidateComponents(basePackage).stream()
          .map(BeanDefinition::getBeanClassName)
          .filter(Objects::nonNull)
          .map(this::loadClass)
          .filter(Class::isInterface)
          .forEach(serviceInterface -> ServiceRouterProxyDefinitions.register(registry, serviceInterface));
    }
  }

  @Override
  public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) throws BeansException {
    // No bean factory changes are required.
  }

  private Class<?> loadClass(final String className) {
    try {
      return ClassUtils.forName(className, resourceLoader == null ? null : resourceLoader.getClassLoader());
    } catch (ClassNotFoundException ex) {
      throw new IllegalStateException("Failed to load routed service class " + className, ex);
    }
  }

  private static final class InterfaceScanner extends ClassPathScanningCandidateComponentProvider {

    private InterfaceScanner(final Environment environment) {
      super(false, environment);
    }

    @Override
    protected boolean isCandidateComponent(final org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
      return beanDefinition.getMetadata().isInterface() && beanDefinition.getMetadata().isIndependent();
    }
  }
}
