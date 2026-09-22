package org.simplepoint.core.query;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** A named, one-hop correlated EXISTS relationship; never a client-provided entity/path. */
@Retention(RetentionPolicy.RUNTIME)
public @interface QueryRelation {
  /** Public relationship alias. */
  String value();

  /** Explicit target entity with its own TableQuery declaration. */
  Class<?> target();

  /** Correlation property on the outer row. */
  String localKey() default "id";

  /** Correlation property on the target row. */
  String foreignKey();

  /** Target's owner property used for SELF data scope. */
  String ownerField() default "createdBy";

  /** Target's department property used for department data scopes. */
  String departmentField() default "createOrgDeptId";
}
