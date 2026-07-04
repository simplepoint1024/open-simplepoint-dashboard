package org.simplepoint.plugin.rbac.resource.base.repository;

import java.util.Collection;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceAncestorRepository;
import org.simplepoint.security.entity.ResourceAncestor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for resource ancestor relations.
 */
@Repository
public interface JpaResourceAncestorRepository extends JpaRepository<ResourceAncestor, String>, ResourceAncestorRepository {

  @Override
  @Modifying
  @Query("delete from ResourceAncestor ra where ra.childId in ?1")
  void deleteChild(Collection<String> childIds);

  @Override
  @Modifying
  @Query("delete from ResourceAncestor ra where ra.ancestorId = ?1")
  void deleteAncestor(String ancestorId);

  @Override
  @Query("select ra.ancestorId from ResourceAncestor ra where ra.childId in ?1")
  Collection<String> findAncestorIdsByChildIdIn(Collection<String> ids);

  @Override
  @Query("select ra.childId from ResourceAncestor ra where ra.ancestorId in ?1")
  Collection<String> findChildIdsByAncestorIds(Collection<String> ids);
}
