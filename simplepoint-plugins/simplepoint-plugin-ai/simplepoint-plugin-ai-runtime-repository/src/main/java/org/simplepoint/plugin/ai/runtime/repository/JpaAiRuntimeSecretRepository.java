package org.simplepoint.plugin.ai.runtime.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeSecret;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeSecretRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for encrypted Runtime secrets.
 */
@Repository
public interface JpaAiRuntimeSecretRepository
    extends BaseRepository<AiRuntimeSecret, String>,
    AiRuntimeSecretRepository {

  @Override
  @Query("""
      select secret from AiRuntimeSecret secret
      where secret.id = :id
        and secret.deletedAt is null
      """)
  Optional<AiRuntimeSecret> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select secret from AiRuntimeSecret secret
      where secret.code = :code
        and secret.scopeType = :scopeType
        and ((:tenantId is null and secret.tenantId is null)
          or secret.tenantId = :tenantId)
        and secret.deletedAt is null
      """)
  Optional<AiRuntimeSecret> findActiveByCodeAndScope(
      @Param("code") String code,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId
  );

  @Override
  @Query("""
      select secret from AiRuntimeSecret secret
      where secret.scopeType = :scopeType
        and ((:tenantId is null and secret.tenantId is null)
          or secret.tenantId = :tenantId)
        and secret.deletedAt is null
      order by secret.createdAt desc
      """)
  Page<AiRuntimeSecret> findAllActiveByScope(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      Pageable pageable
  );
}
