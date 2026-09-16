package org.simplepoint.plugin.ai.runtime.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpDescriptor;

/** Repository contract for imported immutable MCP descriptors. */
public interface AiRuntimeMcpDescriptorRepository
    extends BaseRepository<AiRuntimeMcpDescriptor, String> {

  /** Finds an identical active descriptor inside one management scope. */
  Optional<AiRuntimeMcpDescriptor> findActiveByIdentity(
      AiResourceScope scopeType,
      String tenantId,
      String registryName,
      String serverVersion,
      String contentHash
  );
}
