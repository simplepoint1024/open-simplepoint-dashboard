package org.simplepoint.platform.dictionary;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Selects the authoritative dictionary definition source for a logical module. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleDictionaries {
  /**
   * Returns the pure definition source; metadata ownership is checked against the selected module.
   */
  Class<? extends DictionaryDefinitions> value();
}
