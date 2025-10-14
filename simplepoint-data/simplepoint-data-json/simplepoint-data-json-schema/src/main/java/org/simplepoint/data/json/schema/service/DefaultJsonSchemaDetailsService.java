package org.simplepoint.data.json.schema.service;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.api.security.simple.SimpleFieldPermissions;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.springframework.security.core.GrantedAuthority;

/**
 * Default implementation of JsonSchemaDetailsService.
 */
@AmqpRemoteService
public class DefaultJsonSchemaDetailsService implements JsonSchemaDetailsService {

  private final EntityManager entityManager;

  /**
   * Constructor for DefaultJsonSchemaDetailsService.
   *
   * @param entityManager the EntityManager to be used by this service
   */
  public DefaultJsonSchemaDetailsService(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public Set<SimpleFieldPermissions> loadSchemaPropertiesPermissions(Set<String> roles, String className) {
    String jpql = """
            SELECT new org.simplepoint.api.security.simple.SimpleFieldPermissions(sp.authority, sp.resource, srpr.action)
            FROM Permissions sp
            JOIN RolePermissionsRelevance srpr ON sp.authority = srpr.permissionAuthority
            WHERE srpr.roleAuthority IN :roles
              AND sp.resource LIKE :resourcePrefix and sp.resourceType='field' and srpr.action like '%READ%'
        """;
    List<SimpleFieldPermissions> resultList = entityManager.createQuery(
        jpql, SimpleFieldPermissions.class).setParameter("roles", roles).setParameter("resourcePrefix", className + "%").getResultList();
    return new HashSet<>(resultList);
  }

  @Override
  public Set<SimpleFieldPermissions> loadCurrentUserSchemaPropertiesPermissions(BaseUser currentUser, String className) {
    Collection<? extends GrantedAuthority> authorities = currentUser.getAuthorities();
    Set<String> roles = authorities.stream().map(GrantedAuthority::getAuthority).collect(
        Collectors.toSet());
    return this.loadSchemaPropertiesPermissions(roles, className);
  }
}
