package org.simplepoint.plugin.rbac.resource.api.repository;

import java.util.Collection;
import java.util.List;
import org.simplepoint.security.entity.ResourceAncestor;

/**
 * Repository for resource ancestor relations.
 */
public interface ResourceAncestorRepository {

  /**
   * Saves a resource ancestor relation.
   */
  <S extends ResourceAncestor> S save(S entity);

  /**
   * Saves resource ancestor relations.
   */
  <S extends ResourceAncestor> List<S> saveAll(Iterable<S> entities);

  /**
   * Deletes all ancestor relations for child resources.
   */
  void deleteChild(Collection<String> childIds);

  /**
   * Deletes all descendant relations for an ancestor resource.
   */
  void deleteAncestor(String ancestorId);

  /**
   * Finds ancestor resource ids for child resources.
   */
  Collection<String> findAncestorIdsByChildIdIn(Collection<String> ids);

  /**
   * Finds child resource ids for ancestor resources.
   */
  Collection<String> findChildIdsByAncestorIds(Collection<String> ids);
}
