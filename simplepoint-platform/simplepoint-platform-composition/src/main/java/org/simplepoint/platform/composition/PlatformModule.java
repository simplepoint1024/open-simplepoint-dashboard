package org.simplepoint.platform.composition;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a logical module on its configuration class, independent of its deployment host. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlatformModule {

  /** Returns the stable logical module ID. */
  String id();

  /** Returns the exact module contract revision. */
  int version() default 1;

  /** Returns exclusively owned component package roots, without wildcards. */
  String[] packages();

  /** Returns modules that must be assembled locally before this module. */
  ModuleDependency[] requires() default {};

  /** Returns published interfaces carrying their authoritative RemoteContract metadata. */
  Class<?>[] provides() default {};

  /** Returns ordered startup contributions, whose consumed contracts are derived automatically. */
  Class<? extends ModuleInitializer>[] initializers() default {};

  /** Returns consumed interfaces, whose providers may be local or remote. */
  Class<?>[] consumes() default {};
}
