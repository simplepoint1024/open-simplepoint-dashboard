package org.simplepoint.plugin.oidc.service.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityLink;
import org.simplepoint.plugin.oidc.api.repository.ExternalIdentityLinkRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA repository for external identity bindings. */
@Repository
public interface JpaExternalIdentityLinkRepository
    extends BaseRepository<ExternalIdentityLink, String>, ExternalIdentityLinkRepository {

  @Override
  @Query("""
      select l from ExternalIdentityLink l
      where l.providerId = :providerId
        and l.externalSubject = :externalSubject
        and l.deletedAt is null
      """)
  Optional<ExternalIdentityLink> findActiveByProviderAndSubject(
      @Param("providerId") String providerId,
      @Param("externalSubject") String externalSubject
  );

  @Override
  @Query("""
      select (count(l) > 0) from ExternalIdentityLink l
      where l.providerId = :providerId and l.deletedAt is null
      """)
  boolean existsActiveByProviderId(@Param("providerId") String providerId);
}
