package org.simplepoint.plugin.ai.runtime.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpDescriptor;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeMcpDescriptorRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA repository for imported immutable MCP descriptors. */
@Repository
public interface JpaAiRuntimeMcpDescriptorRepository
    extends BaseRepository<AiRuntimeMcpDescriptor, String>,
    AiRuntimeMcpDescriptorRepository {

  @Override
  @Query("""
      select descriptor from AiRuntimeMcpDescriptor descriptor
      where descriptor.scopeType = :scopeType
        and ((:tenantId is null and descriptor.tenantId is null)
          or descriptor.tenantId = :tenantId)
        and descriptor.registryName = :registryName
        and descriptor.serverVersion = :serverVersion
        and descriptor.contentHash = :contentHash
        and descriptor.deletedAt is null
      """)
  Optional<AiRuntimeMcpDescriptor> findActiveByIdentity(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      @Param("registryName") String registryName,
      @Param("serverVersion") String serverVersion,
      @Param("contentHash") String contentHash
  );
}
