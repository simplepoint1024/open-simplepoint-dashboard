package org.simplepoint.plugin.dna.federation.api.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationJdbcConnectionUser;
import org.simplepoint.plugin.dna.federation.api.pojo.dto.FederationJdbcUserDataSourceAssignDto;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcUserDataSourceItemVo;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcUserOptionVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service contract for JDBC connection-user maintenance.
 */
public interface FederationJdbcConnectionUserService extends BaseService<FederationJdbcConnectionUser, String> {

  /**
   * Pages active JDBC grants.
   *
   * @param attributes query filters
   * @param pageable paging arguments
   * @param <S> result type
   * @return paged grants
   */
  <S extends FederationJdbcConnectionUser> Page<S> limit(Map<String, String> attributes, Pageable pageable);

  /**
   * Finds one enabled grant for the specified catalog and user.
   *
   * @param catalogId catalog id
   * @param userId user id
   * @return enabled grant
   */
  Optional<FederationJdbcConnectionUser> findEnabledGrant(String catalogId, String userId);

  /**
   * Pages enabled datasource options for assignment.
   *
   * @param attributes query filters
   * @param pageable paging arguments
   * @return paged datasource items
   */
  Page<FederationJdbcUserDataSourceItemVo> dataSourceItems(Map<String, String> attributes, Pageable pageable);

  /** Schema for the bounded, credential-free candidate projection. */
  Object dataSourceItemsSchema();

  /** Independently pages one user's enabled grants under the grant's data-scope policy. */
  Page<FederationJdbcConnectionUser> assignedDataSources(
      String userId, Map<String, String> attributes, Pageable pageable);

  /** Updates only operation permissions, after row and field guards, without rewriting grant metadata. */
  FederationJdbcConnectionUser updateOperationPermissions(String grantId, java.util.Set<String> permissions);

  /** Searches enabled system users by email or phone prefix when adding JDBC access. */
  Page<FederationJdbcUserOptionVo> searchUserOptions(String keyword, Pageable pageable);

  /**
   * Returns datasource items for the supplied ids.
   *
   * @param dataSourceIds datasource ids
   * @return datasource items
   */
  Collection<FederationJdbcUserDataSourceItemVo> selectedDataSourceItems(Collection<String> dataSourceIds);

  /**
   * Returns datasource ids currently authorized for a user.
   *
   * @param userId user id
   * @return datasource ids
   */
  Collection<String> authorized(String userId);

  /**
   * Returns enabled datasource grants for a user.
   *
   * @param userId user id
   * @return enabled grants
   */
  Collection<FederationJdbcConnectionUser> enabledGrants(String userId);

  /**
   * Authorizes datasource grants for a user.
   *
   * @param dto assignment payload
   * @return created or existing grants
   */
  Collection<FederationJdbcConnectionUser> authorize(FederationJdbcUserDataSourceAssignDto dto);

  /**
   * Removes datasource grants for a user.
   *
   * @param dto assignment payload
   */
  void unauthorized(FederationJdbcUserDataSourceAssignDto dto);
}
