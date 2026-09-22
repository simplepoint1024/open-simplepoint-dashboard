package org.simplepoint.core.permission;

import java.lang.annotation.*;

/** Explicit contract for a projection controller that does not extend BaseController. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface PermissionPage {
  String value();
  Class<?> model();
}
