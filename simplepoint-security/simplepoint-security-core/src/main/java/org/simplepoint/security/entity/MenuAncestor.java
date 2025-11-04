package org.simplepoint.security.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.Data;
import org.simplepoint.security.entity.id.MenuAncestorId;

/**
 * Entity representing the ancestor-child relationship between menus.
 */
@Data
@Entity
@IdClass(MenuAncestorId.class)
@Table(name = "security_menu_ancestors_rel")
public class MenuAncestor implements Serializable {

  @Id
  private String childId;

  @Id
  private String ancestorId;
}
