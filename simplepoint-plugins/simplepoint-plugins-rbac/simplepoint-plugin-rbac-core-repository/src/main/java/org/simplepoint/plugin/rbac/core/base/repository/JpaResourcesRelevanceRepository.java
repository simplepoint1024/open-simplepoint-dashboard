package org.simplepoint.plugin.rbac.core.base.repository;

import java.util.Collection;
import org.simplepoint.plugin.rbac.core.api.repository.ResourcesRelevanceRepository;
import org.simplepoint.security.entity.ResourcesPermissionsRelevance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing ResourcesRelevance entities.
 * This interface extends BaseRepository and provides additional custom query methods
 * for managing resource relevance based on authorities.
 *
 * @since 0.0.1
 */
@Repository
public interface JpaResourcesRelevanceRepository extends JpaRepository<ResourcesPermissionsRelevance, String>, ResourcesRelevanceRepository {
  @Override
  @Modifying
  @Query("DELETE FROM ResourcesPermissionsRelevance rpr WHERE rpr.resourceId IN ?1")
  void removeAllByAuthorities(Collection<String> resourceIds);

  @Override
  @Modifying
  @Query("DELETE FROM ResourcesPermissionsRelevance rpr WHERE rpr.resourceId = ?1")
  void removeAllByAuthority(String resourceId);

  @Override
  @Modifying
  @Query("DELETE FROM ResourcesPermissionsRelevance rpr WHERE rpr.resourceId = ?1 AND rpr.permissionAuthority IN ?2")
  void unauthorize(String resourceId, Collection<String> permissionAuthority);

  @Override
  @Modifying
  @Query("DELETE FROM ResourcesPermissionsRelevance rpr WHERE rpr.permissionAuthority IN ?1")
  void deleteAllByPermissionAuthorities(Collection<String> permissionAuthorities);

  @Override
  @Query("SELECT permissionAuthority FROM ResourcesPermissionsRelevance WHERE resourceId = ?1")
  Collection<String> authorized(String resourceId);

  @Override
  @Query("SELECT resourceId FROM ResourcesPermissionsRelevance where permissionAuthority in ?1")
  Collection<String> loadAllResourceAuthorities(Collection<String> resourceIds);

  @Override
  default void authorize(Collection<ResourcesPermissionsRelevance> collection) {
    this.saveAll(collection);
  }

  @Override
  @Modifying
  @Query("""
      update ResourcesPermissionsRelevance rpr
      set rpr.permissionAuthority = ?2
      where rpr.permissionAuthority = ?1
      """)
  void updatePermissionAuthority(String oldAuthority, String newAuthority);
}
