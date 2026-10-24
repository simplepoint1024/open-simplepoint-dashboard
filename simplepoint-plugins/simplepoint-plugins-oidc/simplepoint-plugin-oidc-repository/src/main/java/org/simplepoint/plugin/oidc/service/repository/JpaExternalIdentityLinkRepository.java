package org.simplepoint.plugin.oidc.service.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityLink;
import org.simplepoint.plugin.oidc.api.repository.ExternalIdentityLinkRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA repository for external identity bindings. */
@Repository
public interface JpaExternalIdentityLinkRepository
    extends BaseRepository<ExternalIdentityLink, String>, ExternalIdentityLinkRepository {

  @Override
  @Query(
      """
      select l from ExternalIdentityLink l
      where l.providerId = :providerId
        and l.externalSubject = :externalSubject
        and l.deletedAt is null
      """)
  Optional<ExternalIdentityLink> findActiveByProviderAndSubject(
      @Param("providerId") String providerId, @Param("externalSubject") String externalSubject);

  @Override
  @Query(
      """
      select l from ExternalIdentityLink l
      where l.providerId = :providerId
        and l.userId = :userId
        and l.deletedAt is null
      """)
  Optional<ExternalIdentityLink> findActiveByProviderAndUserId(
      @Param("providerId") String providerId, @Param("userId") String userId);

  @Override
  @Query(
      """
      select l from ExternalIdentityLink l
      where l.userId = :userId and l.deletedAt is null
      order by l.createdAt desc, l.id desc
      """)
  List<ExternalIdentityLink> findAllActiveByUserId(@Param("userId") String userId);

  @Override
  @Query(
      """
      select (count(l) > 0) from ExternalIdentityLink l
      where l.providerId = :providerId and l.deletedAt is null
      """)
  boolean existsActiveByProviderId(@Param("providerId") String providerId);

  /** Serializes unlink with an in-flight login metadata update, even for disabled providers. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select l from ExternalIdentityLink l
      where l.providerId = :providerId and l.userId = :userId and l.deletedAt is null
      """)
  Optional<ExternalIdentityLink> findActiveByProviderAndUserIdForUpdate(
      @Param("providerId") String providerId, @Param("userId") String userId);

  /** Locks only the effective binding; an archived ID cannot touch its replacement. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select l from ExternalIdentityLink l
      where l.id = :id and l.providerId = :providerId and l.userId = :userId
        and l.deletedAt is null
        and exists (select p.id from ExternalIdentityProvider p
                    where p.id = l.providerId and p.enabled = true and p.deletedAt is null)
      """)
  Optional<ExternalIdentityLink> findEffectiveByIdForUpdate(
      @Param("id") String id,
      @Param("providerId") String providerId,
      @Param("userId") String userId);
}
