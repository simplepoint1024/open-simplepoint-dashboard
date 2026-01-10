package org.simplepoint.plugin.rbac.menu.base.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuAncestorRepository;
import org.simplepoint.security.entity.MenuAncestor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * JPA Repository for MenuAncestor entity.
 */
@Repository
public interface JpaMenuAncestorRepository extends JpaRepository<MenuAncestor, String>, MenuAncestorRepository {

  @Override
  @Modifying
  @Query("DELETE FROM MenuAncestor ma WHERE ma.childId in ?1")
  void deleteChild(Collection<String> childId);

  @Override
  @Modifying
  @Query("DELETE FROM MenuAncestor ma WHERE ma.ancestorId = ?1")
  void deleteAncestor(String ancestorUuid);

  @Override
  @Query("SELECT ma.ancestorId FROM MenuAncestor ma WHERE ma.childId in ?1")
  Collection<String> findAncestorIdsByChildIdIn(Collection<String> childUuid);

  @Override
  @Query("SELECT ma.childId FROM MenuAncestor ma WHERE ma.ancestorId in ?1")
  Collection<String> findChildIdsByAncestorIds(Collection<String> uuids);
}
