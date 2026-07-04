package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import java.io.Serializable;
import lombok.Data;

/**
 * User summary used to show role-assignment impact.
 */
@Data
public class AccessCenterUserImpactVo implements Serializable {

  private String id;

  private String name;

  private String email;

  private String phoneNumber;

  public AccessCenterUserImpactVo() {
  }

  public AccessCenterUserImpactVo(String id, String name, String email, String phoneNumber) {
    this.id = id;
    this.name = name;
    this.email = email;
    this.phoneNumber = phoneNumber;
  }
}
