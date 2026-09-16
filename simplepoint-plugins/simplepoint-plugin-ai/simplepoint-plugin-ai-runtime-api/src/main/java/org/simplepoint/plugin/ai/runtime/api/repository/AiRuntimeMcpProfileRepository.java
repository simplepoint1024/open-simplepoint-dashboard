package org.simplepoint.plugin.ai.runtime.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Repository contract for scope-owned MCP Runtime Profiles. */
public interface AiRuntimeMcpProfileRepository
    extends BaseRepository<AiRuntimeMcpProfile, String> {

  /** Locks a profile by scope and code for create-or-update operations. */
  Optional<AiRuntimeMcpProfile> findActiveByCodeForUpdate(
      AiResourceScope scopeType,
      String tenantId,
      String code
  );

  /** Locks one active profile before publishing or changing its revision. */
  Optional<AiRuntimeMcpProfile> findActiveByIdForUpdate(String profileId);

  /** Pages active profiles inside one management scope. */
  Page<AiRuntimeMcpProfile> findAllActiveByScope(
      AiResourceScope scopeType,
      String tenantId,
      Pageable pageable
  );
}
