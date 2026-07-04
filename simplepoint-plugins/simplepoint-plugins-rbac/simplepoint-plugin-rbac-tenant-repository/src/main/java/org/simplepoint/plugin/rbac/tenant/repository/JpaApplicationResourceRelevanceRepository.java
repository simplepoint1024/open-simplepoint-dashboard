package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationResourceRelevanceRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for application resource relations.
 */
@Repository
public interface JpaApplicationResourceRelevanceRepository
    extends JpaRepository<ApplicationResourceRelevance, String>, ApplicationResourceRelevanceRepository {

  @Override
  @Modifying
  @Query("delete from ApplicationResourceRelevance arr where arr.applicationCode = ?1 and arr.resourceCode in ?2")
  void unauthorized(String applicationCode, Set<String> resourceCodes);

  @Override
  @Query("select distinct arr.resourceCode from ApplicationResourceRelevance arr where arr.applicationCode = ?1")
  Collection<String> authorized(String applicationCode);

  @Override
  @Modifying
  @Query("delete from ApplicationResourceRelevance arr where arr.applicationCode in ?1")
  void deleteAllByApplicationCodes(Collection<String> applicationCodes);

  @Override
  @Modifying
  @Query("delete from ApplicationResourceRelevance arr where arr.resourceCode in ?1")
  void deleteAllByResourceCodes(Collection<String> resourceCodes);

  @Override
  @Modifying
  @Query("update ApplicationResourceRelevance arr set arr.applicationCode = ?2 where arr.applicationCode = ?1")
  void updateApplicationCode(String oldCode, String newCode);

  @Override
  @Modifying
  @Query("update ApplicationResourceRelevance arr set arr.resourceCode = ?2 where arr.resourceCode = ?1")
  void updateResourceCode(String oldCode, String newCode);
}
