package org.simplepoint.plugin.ai.runtime.api.service;

import java.util.List;
import java.util.Optional;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpProfile;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpRevision;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileImportRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileImportResult;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfilePublishRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileUpdateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Management facade for MCP descriptors, profiles and revisions. */
public interface AiRuntimeMcpProfileService {

  /** Atomically imports an immutable descriptor and creates a profile draft. */
  RuntimeMcpProfileImportResult importDraft(RuntimeMcpProfileImportRequest request);

  /** Pages current-scope Runtime Profiles. */
  Page<AiRuntimeMcpProfile> findAll(Pageable pageable);

  /** Finds one readable Runtime Profile. */
  Optional<AiRuntimeMcpProfile> find(String profileId);

  /** Replaces a profile draft without altering any published revision. */
  AiRuntimeMcpProfile update(
      String profileId,
      RuntimeMcpProfileUpdateRequest request
  );

  /** Publishes the current draft as a new immutable revision. */
  AiRuntimeMcpRevision publish(
      String profileId,
      RuntimeMcpProfilePublishRequest request
  );

  /** Lists immutable revisions for one current-scope profile. */
  List<AiRuntimeMcpRevision> findRevisions(String profileId);

  /** Rolls the profile and any attached Pool back to an existing revision. */
  AiRuntimeMcpProfile activateRevision(String profileId, String revisionId);

  /** Soft-deletes a never-published draft; immutable revisions remain undeletable. */
  void removeDraft(String profileId);
}
