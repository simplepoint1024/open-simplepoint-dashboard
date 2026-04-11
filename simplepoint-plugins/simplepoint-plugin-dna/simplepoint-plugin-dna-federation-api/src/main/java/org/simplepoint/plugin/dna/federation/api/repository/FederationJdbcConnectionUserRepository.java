package org.simplepoint.plugin.dna.federation.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.FederationJdbcConnectionUser;

/**
 * Repository contract for JDBC connection-user grants.
 */
public interface FederationJdbcConnectionUserRepository extends BaseRepository<FederationJdbcConnectionUser, String> {

  /**
   * Finds one active grant by id.
   *
   * @param id grant id
   * @return active grant
   */
  Optional<FederationJdbcConnectionUser> findByIdAndDeletedAtIsNull(String id);

  /**
   * Finds one active grant by catalog and user.
   *
   * @param catalogId catalog id
   * @param userId user id
   * @return active grant
   */
  Optional<FederationJdbcConnectionUser> findByCatalogIdAndUserIdAndDeletedAtIsNull(String catalogId, String userId);

  /**
   * Finds all active grants for a user.
   *
   * @param userId user id
   * @return active grants
   */
  List<FederationJdbcConnectionUser> findAllByUserIdAndDeletedAtIsNull(String userId);

  /**
   * Finds active grants by user and datasource ids.
   *
   * @param userId user id
   * @param catalogIds datasource ids
   * @return active grants
   */
  List<FederationJdbcConnectionUser> findAllByUserIdAndCatalogIdInAndDeletedAtIsNull(
      String userId,
      Collection<String> catalogIds
  );
}
