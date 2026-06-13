package com.simplepoint.service.router.annotation;

import com.simplepoint.service.router.config.ServiceRouterRegistrar;
import com.simplepoint.service.router.config.ServiceRouterRuntimeConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Enables service router provider and consumer infrastructure.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({ServiceRouterRegistrar.class, ServiceRouterRuntimeConfiguration.class})
public @interface EnableServiceRouter {

  /**
   * Base packages to scan for routed service interfaces.
   *
   * @return base packages
   */
  String[] basePackages() default {};

  /**
   * Type-safe package anchors.
   *
   * @return package anchor classes
   */
  Class<?>[] basePackageClasses() default {};
}
