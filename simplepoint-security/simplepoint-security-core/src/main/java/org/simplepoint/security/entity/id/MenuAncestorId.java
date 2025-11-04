package org.simplepoint.security.entity.id;

import java.io.Serializable;
import lombok.Data;

/**
 * Composite key class for MenuAncestor entity.
 */
@Data
public class MenuAncestorId implements Serializable {
  private String childId;
  private String ancestorId;
}
