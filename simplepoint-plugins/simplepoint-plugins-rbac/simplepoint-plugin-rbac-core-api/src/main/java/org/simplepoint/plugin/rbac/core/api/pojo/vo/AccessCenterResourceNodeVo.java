package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;

/**
 * Resource tree node used by the access center.
 */
@Data
public class AccessCenterResourceNodeVo implements Serializable {

  private String id;

  private String type;

  private String label;

  private String alias;

  private String icon;

  private String code;

  private String path;

  private String description;

  private String resourceCode;

  private boolean grantable;

  private boolean checked;

  private boolean partial;

  private Set<String> resourceCodes = new LinkedHashSet<>();

  private List<AccessCenterResourceNodeVo> children = new ArrayList<>();
}
