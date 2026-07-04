package org.simplepoint.security.entity;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Tree node representation for resource management and authorization UI.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceNode extends Resource {
  private List<ResourceNode> children = new ArrayList<>();
}
