package org.simplepoint.plugin.ai.runtime.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpProfile;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeMcpProfileRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA repository for scope-owned MCP Runtime Profiles. */
@Repository
public interface JpaAiRuntimeMcpProfileRepository
    extends BaseRepository<AiRuntimeMcpProfile, String>,
    AiRuntimeMcpProfileRepository {

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select profile from AiRuntimeMcpProfile profile
      where profile.scopeType = :scopeType
        and ((:tenantId is null and profile.tenantId is null)
          or profile.tenantId = :tenantId)
        and profile.code = :code
        and profile.deletedAt is null
      """)
  Optional<AiRuntimeMcpProfile> findActiveByCodeForUpdate(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      @Param("code") String code
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select profile from AiRuntimeMcpProfile profile
      where profile.id = :profileId and profile.deletedAt is null
      """)
  Optional<AiRuntimeMcpProfile> findActiveByIdForUpdate(
      @Param("profileId") String profileId
  );

  @Override
  @Query("""
      select profile from AiRuntimeMcpProfile profile
      where profile.scopeType = :scopeType
        and ((:tenantId is null and profile.tenantId is null)
          or profile.tenantId = :tenantId)
        and profile.deletedAt is null
      order by profile.createdAt desc
      """)
  Page<AiRuntimeMcpProfile> findAllActiveByScope(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      Pageable pageable
  );
}
