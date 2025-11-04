package org.simplepoint.plugin.rbac.menu.api.repository;

import java.util.Collection;
import java.util.List;
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
   * @param childUuid the UUID of the child menu
   */
  void deleteChild(String childUuid);

  /**
   * Deletes MenuAncestor entities by ancestor UUID.
   *
   * @param ancestorUuid the UUID of the ancestor menu
   */
  void deleteAncestor(String ancestorUuid);

  /**
   * Finds ancestors by child UUID.
   *
   * @param uuids the UUID of the child menu
   * @return a collection of Ancestors
   */
  Collection<String> findAncestorUuidsByChildUuids(Collection<String> uuids);

  /**
   * Finds children by ancestor UUID.
   *
   * @param uuids the UUID of the ancestor menu
   * @return a collection of Children
   */
  Collection<String> findChildUuidsByAncestorUuids(Collection<String> uuids);
}
