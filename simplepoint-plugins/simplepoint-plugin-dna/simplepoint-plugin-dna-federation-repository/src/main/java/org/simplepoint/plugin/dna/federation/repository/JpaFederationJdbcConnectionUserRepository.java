package org.simplepoint.plugin.dna.federation.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationJdbcConnectionUser;
import org.simplepoint.plugin.dna.federation.api.repository.FederationJdbcConnectionUserRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for JDBC connection-user grants.
 */
@Repository
public interface JpaFederationJdbcConnectionUserRepository
    extends BaseRepository<FederationJdbcConnectionUser, String>, FederationJdbcConnectionUserRepository {

  @Override
  @Query("""
      select u
      from FederationJdbcConnectionUser u
      where u.id = :id and u.deletedAt is null
      """)
  Optional<FederationJdbcConnectionUser> findByIdAndDeletedAtIsNull(@Param("id") String id);

  @Override
  @Query("""
      select u
      from FederationJdbcConnectionUser u
      where u.catalogId = :catalogId
        and u.userId = :userId
        and u.deletedAt is null
      """)
  Optional<FederationJdbcConnectionUser> findByCatalogIdAndUserIdAndDeletedAtIsNull(
      @Param("catalogId") String catalogId,
      @Param("userId") String userId
  );
}
