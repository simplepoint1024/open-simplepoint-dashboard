package org.simplepoint.plugin.ai.runtime.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpRevision;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeMcpRevisionRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA repository for immutable MCP deployment revisions. */
@Repository
public interface JpaAiRuntimeMcpRevisionRepository
    extends BaseRepository<AiRuntimeMcpRevision, String>,
    AiRuntimeMcpRevisionRepository {

  @Override
  @Query("""
      select coalesce(max(revision.revisionNumber), 0) + 1
      from AiRuntimeMcpRevision revision
      where revision.profileId = :profileId
        and revision.deletedAt is null
      """)
  long nextRevisionNumber(@Param("profileId") String profileId);

  @Override
  @Query("""
      select revision from AiRuntimeMcpRevision revision
      where revision.profileId = :profileId
        and revision.deletedAt is null
      order by revision.revisionNumber desc
      """)
  List<AiRuntimeMcpRevision> findActiveByProfile(
      @Param("profileId") String profileId
  );

  @Override
  @Query("""
      select revision from AiRuntimeMcpRevision revision
      where revision.profileId = :profileId
        and revision.id = :revisionId
        and revision.deletedAt is null
      """)
  Optional<AiRuntimeMcpRevision> findActiveByProfileAndId(
      @Param("profileId") String profileId,
      @Param("revisionId") String revisionId
  );
}
