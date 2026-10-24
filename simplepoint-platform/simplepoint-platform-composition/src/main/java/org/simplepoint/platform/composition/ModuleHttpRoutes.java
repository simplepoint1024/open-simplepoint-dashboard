package org.simplepoint.platform.composition;

import java.util.Set;

/**
 * Technology adapter for reading stable literal HTTP subtrees from authoritative declarations.
 * Implementations have a public no-argument constructor, read only the supplied module's metadata,
 * and never initialize controllers, connect to infrastructure or resolve environment placeholders.
 */
public interface ModuleHttpRoutes {
  /** Returns nonempty absolute paths relative to the module namespace, without wildcards. */
  Set<String> roots(ModuleDescriptor module);
}
