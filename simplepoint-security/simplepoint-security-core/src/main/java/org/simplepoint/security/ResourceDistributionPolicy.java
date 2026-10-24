package org.simplepoint.security;

import java.util.Set;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;

/** Template delivery is independent of public access and ordinary role delegation. */
public final class ResourceDistributionPolicy {
  public static final Set<ResourceType> EXECUTABLE_TYPES =
      Set.of(ResourceType.PAGE, ResourceType.FEATURE, ResourceType.ACTION, ResourceType.API);

  private ResourceDistributionPolicy() {}

  /** Only tenant and personal capabilities can be delivered by application/package templates. */
  public static boolean supportsScope(Set<ResourceScopeType> scopes) {
    return scopes != null
        && (scopes.contains(ResourceScopeType.TENANT)
            || scopes.contains(ResourceScopeType.PERSONAL));
  }

  /**
   * Structural entries travel as ancestors; they are never independently purchased capabilities.
   */
  public static boolean accepts(Resource resource) {
    return resource != null
        && Boolean.FALSE.equals(resource.getDisabled())
        && resource.getDeletedAt() == null
        && resource.getCode() != null
        && !resource.getCode().isBlank()
        && resource.getType() != null
        && EXECUTABLE_TYPES.contains(resource.getType())
        && supportsScope(resource.getScopeTypes());
  }
}
