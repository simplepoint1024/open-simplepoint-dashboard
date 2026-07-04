package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import java.io.Serializable;
import lombok.Data;

/**
 * Lightweight data/field scope information for access-center screens.
 */
@Data
public class AccessCenterScopeVo implements Serializable {

  private String id;

  private String name;

  private String type;

  private String description;

  public AccessCenterScopeVo() {
  }

  public AccessCenterScopeVo(String id, String name, String type, String description) {
    this.id = id;
    this.name = name;
    this.type = type;
    this.description = description;
  }
}
