package org.simplepoint.core;

import java.io.Serializable;
import java.util.Set;

/** Effective row policy for one page resource; translated to an ORM predicate by repositories. */
public record ResourceDataScope(String type, Set<String> departmentIds, boolean includeSelf)
    implements Serializable {
  public ResourceDataScope {
    departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
  }
}
