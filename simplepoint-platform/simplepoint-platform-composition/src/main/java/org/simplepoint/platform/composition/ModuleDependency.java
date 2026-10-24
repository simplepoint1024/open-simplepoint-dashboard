package org.simplepoint.platform.composition;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares an exact, co-located module dependency. */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleDependency {

  /** Returns the required logical module ID. */
  String id();

  /** Returns its required module contract revision. */
  int version() default 1;
}
