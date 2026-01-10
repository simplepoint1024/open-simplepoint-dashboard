package org.simplepoint.plugin.rbac.menu.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.security.entity.MenuAncestor;

/**
 * Repository interface for MenuAncestor entity.
 */
public interface MenuAncestorRepository {

  /**
   * Saves a MenuAncestor entity.
   *
   * @param entity the MenuAncestor entity to save
   * @param <S>    the type of the MenuAncestor entity
   * @return the saved MenuAncestor entity
   */
  <S extends MenuAncestor> S save(S entity);

  /**
   * Saves multiple MenuAncestor entities.
   *
   * @param entities the iterable of MenuAncestor entities to save
   * @param <S>      the type of the MenuAncestor entities
   * @return the list of saved MenuAncestor entities
   */
  <S extends MenuAncestor> List<S> saveAll(Iterable<S> entities);

  /**
   * Deletes MenuAncestor entities by child UUID.
   *
   * @param childId the UUID of the child menu
   */
  void deleteChild(Collection<String> childId);

  /**
   * Deletes MenuAncestor entities by ancestor UUID.
   *
   * @param ancestorUuid the UUID of the ancestor menu
   */
  void deleteAncestor(String ancestorUuid);

  /**
   * Finds ancestors by child UUID.
   *
   * @param ids the UUID of the child menu
   * @return a collection of Ancestors
   */
  Collection<String> findAncestorIdsByChildIdIn(Collection<String> ids);

  /**
   * Finds children by ancestor UUID.
   *
   * @param ids the UUID of the ancestor menu
   * @return a collection of Children
   */
  Collection<String> findChildIdsByAncestorIds(Collection<String> ids);
}
