package org.simplepoint.plugin.rbac.menu.base.repository;

import java.util.Collection;
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
  @Query("DELETE FROM MenuAncestor ma WHERE ma.childUuid = ?1")
  void deleteChild(String childUuid);

  @Override
  @Modifying
  @Query("DELETE FROM MenuAncestor ma WHERE ma.ancestorUuid = ?1")
  void deleteAncestor(String ancestorUuid);

  @Override
  @Query("SELECT ma.ancestorUuid FROM MenuAncestor ma WHERE ma.childUuid in ?1")
  Collection<String> findAncestorUuidsByChildUuids(Collection<String> childUuid);


  @Override
  @Query("SELECT ma.childUuid FROM MenuAncestor ma WHERE ma.ancestorUuid in ?1")
  Collection<String> findChildUuidsByAncestorUuids(Collection<String> uuids);
}
