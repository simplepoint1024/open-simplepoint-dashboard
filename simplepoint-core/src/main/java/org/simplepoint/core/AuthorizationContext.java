package org.simplepoint.core;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 表示授权上下文的接口，提供获取当前用户授权相关信息的方法。
 *
 * <p>An interface representing the authorization context, providing methods to retrieve
 * authorization-related information for the current user.
 */
@Getter
@SuppressWarnings("checkstyle:OverloadMethodsDeclarationOrder")
public class AuthorizationContext implements Serializable {
  private String contextId;
  private String userId;
  private Boolean isAdministrator;
  private Collection<String> roles;
  private Collection<String> resources;
  private Long version;
  private Map<String, String> attributes;

  /**
   * Runtime scope for the current request: PLATFORM, TENANT, or PERSONAL.
   */
  private AuthorizationScopeType scopeType;

  /**
   * Actor role within the active scope.
   */
  private AuthorizationActorRole actorRole;

  /**
   * The effective data scope type name for row-level access control.
   * Null means no data scope restriction has been configured.
   * This is the most permissive scope across all of the user's roles.
   * Values correspond to {@code DataScopeType} enum names.
   */
  private String dataScopeType;

  /**
   * Effective department IDs for CUSTOM data scope.
   * Only populated when dataScopeType is "CUSTOM".
   */
  private Set<String> deptIds;

  /**
   * Whether the effective data scope also includes records owned by the current user.
   * Used when multiple roles combine SELF with department/custom scopes.
   */
  private Boolean dataScopeIncludeSelf;

  /**
   * Field-level access rules keyed by "ClassName#fieldName".
   * Values are the {@code FieldAccessType} name (VISIBLE, EDITABLE, MASKED, HIDDEN).
   * The most permissive access type across all of the user's roles is stored.
   */
  private Map<String, String> fieldPermissions;

  /**
   * Sets the context ID if it has not been set before.
   *
   * @param contextId the context ID to set
   */
  public void setContextId(String contextId) {
    if (this.contextId == null) {
      this.contextId = contextId;
    }
  }

  /**
   * Sets the user ID if it has not been set before.
   *
   * @param userId the user ID to set
   */
  public void setUserId(String userId) {
    if (this.userId == null) {
      this.userId = userId;
    }
  }

  /**
   * Sets the administrator status if it has not been set before.
   *
   * @param isAdministrator the administrator status to set
   */
  public void setIsAdministrator(Boolean isAdministrator) {
    if (this.isAdministrator == null) {
      this.isAdministrator = isAdministrator;
    }
  }

  /**
   * Sets the roles if they have not been set before.
   *
   * @param roles the list of roles to set
   */
  public void setRoles(Collection<String> roles) {
    if (this.roles == null) {
      this.roles = roles == null ? Collections.emptySet() : roles;
    }
  }

  /**
   * Sets the resources if they have not been set before.
   *
   * @param resources the resource codes to set
   */
  public void setResources(Collection<String> resources) {
    if (this.resources == null) {
      this.resources = resources == null ? Collections.emptySet() : resources;
    }
  }

  /**
   * Sets the version if it has not been set before.
   *
   * @param version the version to set
   */
  public void setVersion(Long version) {
    if (this.version == null) {
      this.version = version;
    }
  }

  /**
   * Sets the attributes map if it has not been set before.
   *
   * @param attributes the map of attributes to set
   */
  public void setAttributes(Map<String, String> attributes) {
    if (this.attributes == null) {
      this.attributes = attributes == null ? Collections.emptyMap() : attributes;
    }
  }

  /**
   * Sets the runtime authorization scope if it has not been set before.
   *
   * @param scopeType the runtime scope to set
   */
  public void setScopeType(AuthorizationScopeType scopeType) {
    if (this.scopeType == null) {
      this.scopeType = scopeType;
    }
  }

  /**
   * Replaces the runtime authorization scope.
   *
   * <p>Use this only when rebuilding or refreshing a context. Normal request
   * construction should continue using the write-once setters.</p>
   *
   * @param scopeType the runtime scope to set
   */
  public void replaceScopeType(AuthorizationScopeType scopeType) {
    this.scopeType = scopeType;
  }

  /**
   * Sets the runtime authorization scope by enum name if it has not been set before.
   *
   * @param scopeType the runtime scope enum name
   */
  public void setScopeType(String scopeType) {
    if (this.scopeType == null && scopeType != null && !scopeType.isBlank()) {
      this.scopeType = AuthorizationScopeType.valueOf(scopeType);
    }
  }

  /**
   * Replaces the runtime authorization scope by enum name.
   *
   * @param scopeType the runtime scope enum name
   */
  public void replaceScopeType(String scopeType) {
    this.scopeType = scopeType == null || scopeType.isBlank() ? null : AuthorizationScopeType.valueOf(scopeType);
  }

  /**
   * Sets the actor role within the active scope if it has not been set before.
   *
   * @param actorRole the actor role to set
   */
  public void setActorRole(AuthorizationActorRole actorRole) {
    if (this.actorRole == null) {
      this.actorRole = actorRole;
    }
  }

  /**
   * Replaces the actor role within the active scope.
   *
   * <p>Use this only when rebuilding or refreshing a context. Normal request
   * construction should continue using the write-once setters.</p>
   *
   * @param actorRole the actor role to set
   */
  public void replaceActorRole(AuthorizationActorRole actorRole) {
    this.actorRole = actorRole;
  }

  /**
   * Sets the actor role by enum name if it has not been set before.
   *
   * @param actorRole the actor role enum name
   */
  public void setActorRole(String actorRole) {
    if (this.actorRole == null && actorRole != null && !actorRole.isBlank()) {
      this.actorRole = AuthorizationActorRole.valueOf(actorRole);
    }
  }

  /**
   * Replaces the actor role by enum name.
   *
   * @param actorRole the actor role enum name
   */
  public void replaceActorRole(String actorRole) {
    this.actorRole = actorRole == null || actorRole.isBlank() ? null : AuthorizationActorRole.valueOf(actorRole);
  }

  /**
   * Merges request-scoped attributes into the current context.
   * Existing values are overwritten so the active request headers remain authoritative.
   *
   * @param attributes request attributes to merge
   */
  public void mergeAttributes(Map<String, String> attributes) {
    if (attributes == null || attributes.isEmpty()) {
      return;
    }
    Map<String, String> merged = new HashMap<>();
    if (this.attributes != null) {
      merged.putAll(this.attributes);
    }
    attributes.forEach((key, value) -> {
      if (key != null && value != null) {
        merged.put(key, value);
      }
    });
    this.attributes = merged;
  }

  /**
   * Sets the data scope type if it has not been set before.
   *
   * @param dataScopeType the data scope type name to set
   */
  public void setDataScopeType(String dataScopeType) {
    if (this.dataScopeType == null) {
      this.dataScopeType = dataScopeType;
    }
  }

  /**
   * Sets the department IDs if they have not been set before.
   *
   * @param deptIds the set of department IDs to set
   */
  public void setDeptIds(Set<String> deptIds) {
    if (this.deptIds == null) {
      this.deptIds = deptIds == null ? Collections.emptySet() : deptIds;
    }
  }

  /**
   * Sets whether the effective data scope also includes the current user's own records.
   *
   * @param dataScopeIncludeSelf true when SELF should be OR-ed into the effective row predicate
   */
  public void setDataScopeIncludeSelf(Boolean dataScopeIncludeSelf) {
    if (this.dataScopeIncludeSelf == null) {
      this.dataScopeIncludeSelf = dataScopeIncludeSelf == null ? Boolean.FALSE : dataScopeIncludeSelf;
    }
  }

  public void setFieldPermissions(Map<String, String> fieldPermissions) {
    if (this.fieldPermissions == null) {
      this.fieldPermissions = fieldPermissions == null ? Collections.emptyMap() : fieldPermissions;
    }
  }

  /**
   * Retrieves the value of a specific attribute by its key.
   *
   * @param key the key of the attribute to retrieve
   * @return the value of the attribute, or null if the attributes map is null or the key does not exist
   */
  public String getAttribute(String key) {
    if (this.attributes != null) {
      return this.attributes.get(key);
    }
    return null;
  }

  /**
   * Converts roles and resource codes into Spring Security authorities.
   *
   * @return authorities representing the roles and resource codes in the authorization context
   */
  public Collection<GrantedAuthority> asAuthorities() {
    var authorities = new java.util.HashSet<GrantedAuthority>();
    if (Boolean.TRUE.equals(this.isAdministrator)
        && this.scopeType == AuthorizationScopeType.PLATFORM) {
      authorities.add(new SimpleGrantedAuthority("ROLE_Administrator"));
    }
    if (this.resources != null) {
      this.resources.forEach(resource -> authorities.add(new SimpleGrantedAuthority(resource)));
    }
    if (this.roles != null) {
      this.roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    return authorities;
  }
}
