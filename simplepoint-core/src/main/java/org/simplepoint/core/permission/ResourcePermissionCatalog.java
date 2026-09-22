package org.simplepoint.core.permission;

import java.util.Map;

/** Local standard-controller capabilities, published with the owning module's resources. */
public interface ResourcePermissionCatalog {
  Map<String, PermissionPageDescriptor> entries();
}
