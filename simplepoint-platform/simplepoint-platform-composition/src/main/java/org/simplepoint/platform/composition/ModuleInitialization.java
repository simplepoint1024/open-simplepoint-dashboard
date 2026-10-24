package org.simplepoint.platform.composition;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the remote contracts needed by a reusable module initializer. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleInitialization {
  /** Returns the framework stage; base data must exist before declarations are registered. */
  ModuleInitializationStage stage() default ModuleInitializationStage.REGISTRATION;

  /** True consumes the module's declared complete resource snapshot identity. */
  boolean registersResources() default false;

  /** Returns startup dependencies, included in the consuming module's deployment plan. */
  Class<?>[] consumes() default {};
}
