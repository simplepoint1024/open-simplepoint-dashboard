package org.simplepoint.security;

import java.util.Optional;
import java.util.Set;
import org.simplepoint.security.entity.ResourceScopeType;

/** Read-only declared resource scopes, shared by interactive and background authorization. */
public interface ResourceScopeCatalog {

  /** Missing declarations must fail closed; this catalog does not grant resource access. */
  Optional<Set<ResourceScopeType>> findScopes(String resourceCode);
}
