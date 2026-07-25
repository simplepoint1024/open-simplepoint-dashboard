package org.simplepoint.plugin.oidc.service.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.repository.ExternalIdentityProviderRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA repository for external identity-provider configurations. */
@Repository
public interface JpaExternalIdentityProviderRepository
    extends BaseRepository<ExternalIdentityProvider, String>,
    ExternalIdentityProviderRepository {

  @Override
  @Query("""
      select p from ExternalIdentityProvider p
      where p.id = :id and p.deletedAt is null
      """)
  Optional<ExternalIdentityProvider> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select p from ExternalIdentityProvider p
      where lower(p.registrationId) = lower(:registrationId) and p.deletedAt is null
      """)
  Optional<ExternalIdentityProvider> findActiveByRegistrationId(
      @Param("registrationId") String registrationId
  );

  @Override
  @Query("""
      select p from ExternalIdentityProvider p
      where p.enabled = true and p.deletedAt is null
      order by p.sortOrder asc, p.displayName asc
      """)
  List<ExternalIdentityProvider> findAllActiveEnabled();
}
