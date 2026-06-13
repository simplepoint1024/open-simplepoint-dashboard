package com.simplepoint.service.router.config;

import com.simplepoint.service.router.annotation.EnableServiceRouter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;

/**
 * Registers the routed service interface scanner activated by {@link EnableServiceRouter}.
 */
public class ServiceRouterRegistrar implements ImportBeanDefinitionRegistrar {

  private static final String REGISTRAR_BEAN_NAME =
      ServiceRouterProxyBeanDefinitionRegistryPostProcessor.class.getName();

  @Override
  public void registerBeanDefinitions(
      final AnnotationMetadata importingClassMetadata,
      final BeanDefinitionRegistry registry
  ) {
    if (registry.containsBeanDefinition(REGISTRAR_BEAN_NAME)) {
      return;
    }
    String[] basePackages = resolveBasePackages(importingClassMetadata);
    BeanDefinitionBuilder builder = BeanDefinitionBuilder
        .genericBeanDefinition(ServiceRouterProxyBeanDefinitionRegistryPostProcessor.class)
        .addConstructorArgValue(basePackages);
    registry.registerBeanDefinition(REGISTRAR_BEAN_NAME, builder.getBeanDefinition());
  }

  private static String[] resolveBasePackages(final AnnotationMetadata importingClassMetadata) {
    Map<String, Object> attributes = importingClassMetadata
        .getAnnotationAttributes(EnableServiceRouter.class.getName());
    AnnotationAttributes annotationAttributes = AnnotationAttributes.fromMap(attributes);
    Set<String> basePackages = new LinkedHashSet<>();
    if (annotationAttributes != null) {
      for (String basePackage : annotationAttributes.getStringArray("basePackages")) {
        if (!basePackage.isBlank()) {
          basePackages.add(basePackage);
        }
      }
      for (Class<?> marker : annotationAttributes.getClassArray("basePackageClasses")) {
        basePackages.add(ClassUtils.getPackageName(marker));
      }
    }
    if (basePackages.isEmpty()) {
      basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
    }
    return basePackages.toArray(String[]::new);
  }
}
