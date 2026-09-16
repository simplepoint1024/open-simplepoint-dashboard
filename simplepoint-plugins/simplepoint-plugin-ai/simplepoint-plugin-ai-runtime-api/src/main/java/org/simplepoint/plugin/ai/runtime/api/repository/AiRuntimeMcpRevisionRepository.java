package org.simplepoint.plugin.ai.runtime.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpRevision;

/** Repository contract for immutable MCP deployment revisions. */
public interface AiRuntimeMcpRevisionRepository
    extends BaseRepository<AiRuntimeMcpRevision, String> {

  /** Returns the next monotonically increasing revision number. */
  long nextRevisionNumber(String profileId);

  /** Lists all active revisions for one profile, newest first. */
  List<AiRuntimeMcpRevision> findActiveByProfile(String profileId);

  /** Finds one active revision belonging to the supplied profile. */
  Optional<AiRuntimeMcpRevision> findActiveByProfileAndId(
      String profileId,
      String revisionId
  );
}
