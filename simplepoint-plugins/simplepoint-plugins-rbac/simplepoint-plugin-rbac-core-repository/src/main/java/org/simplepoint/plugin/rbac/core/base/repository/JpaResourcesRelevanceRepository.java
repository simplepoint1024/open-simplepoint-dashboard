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
  @Query("DELETE FROM ResourcesPermissionsRelevance rpr WHERE rpr.resourceAuthority IN ?1")
  void removeAllByAuthorities(Collection<String> resourceAuthorities);

  @Override
  @Modifying
  @Query("DELETE FROM ResourcesPermissionsRelevance rpr WHERE rpr.resourceAuthority = ?1")
  void removeAllByAuthority(String resourceAuthority);

  @Override
  @Modifying
  @Query("DELETE FROM ResourcesPermissionsRelevance rpr WHERE rpr.resourceAuthority = ?1 AND rpr.permissionAuthority IN ?2")
  void unauthorize(String authority, Collection<String> permissionAuthority);

  @Override
  @Query("SELECT permissionAuthority FROM ResourcesPermissionsRelevance WHERE resourceAuthority = ?1")
  Collection<String> authorized(String resourceAuthority);

  @Override
  @Query("SELECT resourceAuthority FROM ResourcesPermissionsRelevance where permissionAuthority in ?1")
  Collection<String> loadAllResourceAuthorities(Collection<String> resourceAuthorities);

  @Override
  default void authorize(Collection<ResourcesPermissionsRelevance> collection) {
    this.saveAll(collection);
  }
}
