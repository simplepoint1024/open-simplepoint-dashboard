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
  @Query("DELETE FROM ResourcesPermissionsRelevance rpr WHERE rpr.resourceId = ?1 AND rpr.permissionId IN ?2")
  void unauthorize(String resourceId, Collection<String> permissionIds);

  @Override
  @Query("SELECT permissionId FROM ResourcesPermissionsRelevance WHERE resourceId = ?1")
  Collection<String> authorized(String resourceId);

  @Override
  @Query("SELECT resourceId FROM ResourcesPermissionsRelevance where permissionId in ?1")
  Collection<String> loadAllResourceAuthorities(Collection<String> resourceIds);

  @Override
  default void authorize(Collection<ResourcesPermissionsRelevance> collection) {
    this.saveAll(collection);
  }
}
