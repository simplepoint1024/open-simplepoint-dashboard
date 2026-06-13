package com.simplepoint.service.router.annotation;

import com.simplepoint.service.router.invocation.NoFallback;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a transport-neutral service contract.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RoutedService {

  /**
   * Stable service name, for example {@code user.UserService}.
   *
   * @return service name
   */
  String name();

  /**
   * Contract version.
   *
   * @return version
   */
  String version() default "1.0";

  /**
   * Invocation timeout in milliseconds.
   *
   * @return timeout
   */
  long timeout() default 3000L;

  /**
   * Remote invocation retry count.
   *
   * @return retry count
   */
  int retries() default 0;

  /**
   * Fallback implementation type.
   *
   * @return fallback class
   */
  Class<?> fallback() default NoFallback.class;
}
