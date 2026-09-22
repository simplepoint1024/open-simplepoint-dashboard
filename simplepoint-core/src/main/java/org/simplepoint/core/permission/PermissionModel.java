package org.simplepoint.core.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Overrides the standard entity permission contract; infrastructure models can opt out. */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PermissionModel {
  String page() default "";
  boolean data() default true;
  boolean fields() default true;
  String owner() default "createdBy";
  String department() default "createOrgDeptId";
}
