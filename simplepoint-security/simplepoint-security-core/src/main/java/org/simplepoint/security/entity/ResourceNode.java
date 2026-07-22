package org.simplepoint.security.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Tree node representation for resource management and authorization UI.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceNode extends Resource {
  private List<ResourceNode> children = new ArrayList<>();
  private Boolean hasChildren;

  /** Scope boundary inherited from the direct parent, used by resource management forms. */
  @Schema(hidden = true)
  private Set<ResourceScopeType> parentScopeTypes;
}
