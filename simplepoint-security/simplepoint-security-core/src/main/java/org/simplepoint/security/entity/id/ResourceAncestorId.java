package org.simplepoint.security.entity.id;

import java.io.Serializable;
import lombok.Data;

/**
 * Composite key for resource ancestor relations.
 */
@Data
public class ResourceAncestorId implements Serializable {
  private String childId;
  private String ancestorId;
}
