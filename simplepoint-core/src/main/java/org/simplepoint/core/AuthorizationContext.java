package org.simplepoint.core;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 表示授权上下文的接口，提供获取当前用户授权相关信息的方法
 * An interface representing the authorization context, providing methods to retrieve authorization-related information for the current user.
 */
@Getter
public class AuthorizationContext implements Serializable {
  private String contextId;
  private String userId;
  private Boolean isAdministrator;
  private Collection<String> roles;
  private Collection<String> permissions;
  private Long version;
  private Map<String, String> attributes;

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
   * Field-level access permissions keyed by "ClassName#fieldName".
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
   * Sets the permissions if they have not been set before.
   *
   * @param permissions the list of permissions to set
   */
  public void setPermissions(Collection<String> permissions) {
    if (this.permissions == null) {
      this.permissions = permissions == null ? Collections.emptySet() : permissions;
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
   * Sets the field permissions map if it has not been set before.
   *
   * @param fieldPermissions the map of field permissions to set
   */
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
   * Converts the roles and permissions in the authorization context into a collection of GrantedAuthority objects.
   *
   * @return a collection of GrantedAuthority objects representing the roles and permissions in the authorization context
   */
  public Collection<GrantedAuthority> asAuthorities() {
    var authorities = new java.util.HashSet<GrantedAuthority>();
    if (this.isAdministrator != null && this.isAdministrator) {
      authorities.add(new SimpleGrantedAuthority("ROLE_Administrator"));
    }
    if (this.permissions != null) {
      this.permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
    }
    if (this.roles != null) {
      this.roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    return authorities;
  }
}
