package org.simplepoint.security;

import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.security.entity.Resource;
import org.springframework.beans.BeanUtils;

/**
 * Declarative resource tree item used by modules and plugins during startup.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceDeclaration extends Resource {
  private Set<ResourceDeclaration> children;

  /**
   * Converts the declaration into a persisted resource entity.
   */
  public Resource toResource() {
    Resource resource = new Resource();
    BeanUtils.copyProperties(this, resource);
    return resource;
  }
}
