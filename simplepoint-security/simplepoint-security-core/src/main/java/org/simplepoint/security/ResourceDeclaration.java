package org.simplepoint.security;

import java.util.Set;
import lombok.Data;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;

/**
 * Declarative resource tree item used by modules and plugins during startup. Contains no
 * persistence identity, audit state or management UI selection state.
 */
@Data
public class ResourceDeclaration {
  private String permissionMetadata;
  private String code;
  private String name;
  private String alias;
  private String title;
  private String label;
  private ResourceType type;
  private Set<ResourceScopeType> scopeTypes;
  private String parentId;
  private ResourceParentReference parentReference;
  private Boolean sharedDirectory;
  private String pluginId;
  private String path;
  private String component;
  private String icon;
  private Integer sort;
  private String routeKind;
  private String method;
  private String pattern;
  private String description;
  private Boolean publicAccess;
  private Boolean requireOrgTenant;
  private Boolean grantable;
  private Boolean disabled;
  private Boolean danger;
  private Set<ResourceDeclaration> children;
}
