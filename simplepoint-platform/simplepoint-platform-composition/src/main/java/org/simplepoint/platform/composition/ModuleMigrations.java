package org.simplepoint.platform.composition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares module-owned migrations at the conventional db/manifest.properties asset. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleMigrations {
  /** False explicitly declares no owned SQL schema; shared infrastructure remains separate. */
  boolean ownsSchema() default true;
}
