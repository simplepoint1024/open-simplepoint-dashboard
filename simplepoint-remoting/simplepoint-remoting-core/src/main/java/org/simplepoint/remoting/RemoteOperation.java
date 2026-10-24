package org.simplepoint.remoting;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares whether repeating an operation after an ambiguous transport failure is safe.
 * Writes require durable idempotency before opting in; timeouts do not prove non-execution.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RemoteOperation {

  /** Returns whether the operation may be retried or use a transport fallback. */
  boolean idempotent() default false;
}
