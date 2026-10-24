package org.simplepoint.events.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.simplepoint.platform.composition.ModuleSharedSchemas;

/** Associates a logical module declaration with its transport-independent event manifest. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ModuleSharedSchemas("platform-events")
public @interface ModuleEvents {
  /** Returns a pure, public no-argument manifest; it must not access infrastructure. */
  Class<? extends EventDeclarations> value();
}
