package org.simplepoint.platform.composition;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stable resource registration identity, independent of adapter module and physical host names. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleResourceOwner {
  /** Returns the owner of the complete resources.json declaration, not a service credential. */
  String value();
}
