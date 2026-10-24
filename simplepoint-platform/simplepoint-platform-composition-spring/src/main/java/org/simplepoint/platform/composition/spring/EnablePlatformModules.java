package org.simplepoint.platform.composition.spring;

import com.simplepoint.service.router.config.ServiceRouterRuntimeConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Selects module roots, discovers their local dependency closure and enables their routed APIs. Use
 * on a host configuration without a global business-component scan.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({
  PlatformModuleRegistrar.class,
  ServiceRouterRuntimeConfiguration.class,
  ModuleCredentialConfiguration.class
})
public @interface EnablePlatformModules {

  /** Returns default roots, overridden by the host's simplepoint.modules.active property. */
  String[] value() default {};
}
