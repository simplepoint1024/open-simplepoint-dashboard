package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import org.simplepoint.security.entity.ResourcesPermissionsRelevance;

/**
 * ResourcesRepository provides an interface for managing Resource entities.
 * It extends BaseRepository to provide CRUD operations for Resource entities.
 *
 * @since 0.0.1
 */
public interface ResourcesRelevanceRepository {

  /**
   * Removes all resources of a specific type associated with a given authority.
   *
   * @param authority the authority associated with the resources
   */
  void removeAllByAuthority(String authority);

  /**
   * Removes all resources of a specific type associated with a collection of authorities.
   *
   * @param authorities the collection of authorities associated with the resources
   */
  void removeAllByAuthorities(Collection<String> authorities);

  /**
   * Revokes a specific authority from resources of a given type.
   *
   * @param authority           the authority to be revoked
   * @param resourceAuthorities the collection of resource authorities to be affected
   */
  void unauthorize(String authority, Collection<String> resourceAuthorities);

  /**
   * Authorizes and retrieves resource authorities of a specific type associated with a given authority.
   *
   * @param resourceAuthority the authority to be checked
   * @return a collection of authorized resource authorities
   */
  Collection<String> authorized(String resourceAuthority);

  /**
   * Loads all resource authorities of a specific type associated with a collection of resource authorities.
   *
   * @param resourceAuthorities the collection of resource authorities to be loaded
   * @return a collection of resource authorities
   */
  Collection<String> loadAllResourceAuthorities(Collection<String> resourceAuthorities);

  /**
   * Authorizes a collection of ResourcesPermissionsRelevance entities.
   *
   * @param collection the collection of ResourcesPermissionsRelevance entities to be authorized
   */
  void authorize(Collection<ResourcesPermissionsRelevance> collection);
}
