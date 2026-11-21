package org.simplepoint.events.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Identifies a logical event owner hosted alongside a legacy service's default owner. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LegacyEventOwner {
  String value();
}
