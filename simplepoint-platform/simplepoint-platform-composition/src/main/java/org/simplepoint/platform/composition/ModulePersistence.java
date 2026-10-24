package org.simplepoint.platform.composition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares persistence ownership that cannot be inferred from a module's component roots. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModulePersistence {
  /** Additional entity roots owned by this module, without scanning their components. */
  String[] entityPackages() default {};

  /** Local modules whose tables participate in the same persistence unit and transactions. */
  String[] sameStoreAs() default {};
}
