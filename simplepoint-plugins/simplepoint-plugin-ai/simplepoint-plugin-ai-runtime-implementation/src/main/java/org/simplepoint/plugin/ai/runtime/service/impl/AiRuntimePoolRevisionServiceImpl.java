package org.simplepoint.plugin.ai.runtime.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpProfile;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpRevision;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolRevisionRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeMcpProfileRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeMcpRevisionRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimePoolRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeWorkloadRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimePoolRevisionService;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSecretService;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeEgressPolicyCodec;
import org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeProfileCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional revision rollout and rollback for existing Runtime Pools. */
@Service
public class AiRuntimePoolRevisionServiceImpl
    implements AiRuntimePoolRevisionService {

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private final AiRuntimePoolRepository poolRepository;

  private final AiRuntimeWorkloadRepository workloadRepository;

  private final AiRuntimeMcpProfileRepository profileRepository;

  private final AiRuntimeMcpRevisionRepository revisionRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiRuntimeSecretService secretService;

  private final AiRuntimeEgressPolicyCodec egressPolicyCodec;

  private final AiRuntimeProfileCodec profileCodec;

  /** Creates the immutable revision rollout service. */
  public AiRuntimePoolRevisionServiceImpl(
      final AiRuntimePoolRepository poolRepository,
      final AiRuntimeWorkloadRepository workloadRepository,
      final AiRuntimeMcpProfileRepository profileRepository,
      final AiRuntimeMcpRevisionRepository revisionRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiRuntimeSecretService secretService,
      final AiRuntimeEgressPolicyCodec egressPolicyCodec,
      final AiRuntimeProfileCodec profileCodec
  ) {
    this.poolRepository = poolRepository;
    this.workloadRepository = workloadRepository;
    this.profileRepository = profileRepository;
    this.revisionRepository = revisionRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.secretService = secretService;
    this.egressPolicyCodec = egressPolicyCodec;
    this.profileCodec = profileCodec;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimePool bind(
      final String poolId,
      final RuntimePoolRevisionRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException("Runtime revision binding is required");
    }
    AiRuntimePool pool = poolRepository.findActiveByIdForUpdate(
        identifier(poolId, "Runtime pool ID")
    ).orElseThrow(() -> new IllegalArgumentException(
        "Runtime pool does not exist"
    ));
    scopeAccessPolicy.assertCanManageOwnedResource(
        pool.getScopeType(), pool.getTenantId()
    );
    AiRuntimeMcpProfile profile = requireProfile(request.profileId());
    assertSameOwner(pool, profile);
    AiRuntimeMcpRevision revision = requireRevision(
        profile.getId(), request.revisionId()
    );
    return apply(pool, profile, revision, Instant.now());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<AiRuntimePool> activateAttached(
      final String profileId,
      final String revisionId
  ) {
    AiRuntimeMcpProfile profile = requireProfile(profileId);
    scopeAccessPolicy.assertCanManageOwnedResource(
        profile.getScopeType(), profile.getTenantId()
    );
    AiRuntimeMcpRevision revision = requireRevision(
        profile.getId(), revisionId
    );
    Instant now = Instant.now();
    return poolRepository.findActiveByRuntimeProfileForUpdate(profile.getId())
        .stream()
        .map(pool -> {
          assertSameOwner(pool, profile);
          return apply(pool, profile, revision, now);
        })
        .toList();
  }

  private AiRuntimeMcpProfile requireProfile(final String profileId) {
    return profileRepository.findById(identifier(
        profileId, "Runtime Profile ID"
    )).filter(profile -> profile.getDeletedAt() == null)
        .orElseThrow(() -> new IllegalArgumentException(
            "Runtime Profile does not exist"
        ));
  }

  private AiRuntimeMcpRevision requireRevision(
      final String profileId,
      final String revisionId
  ) {
    AiRuntimeMcpRevision revision = revisionRepository
        .findActiveByProfileAndId(
            profileId,
            identifier(revisionId, "Runtime revision ID")
        ).orElseThrow(() -> new IllegalArgumentException(
            "Runtime revision does not exist"
        ));
    if (revision.getImageReference() == null
        || revision.getImageDigest() == null) {
      throw new IllegalArgumentException(
          "Only a published OCI revision can be bound to a Runtime Pool"
      );
    }
    return revision;
  }

  private AiRuntimePool apply(
      final AiRuntimePool pool,
      final AiRuntimeMcpProfile profile,
      final AiRuntimeMcpRevision revision,
      final Instant now
  ) {
    RuntimeMcpProfileSpec spec = profileCodec.decode(revision.getProfileJson());
    requireSupportedOci(spec);
    String networkMode = networkMode(spec.network().mode());
    pool.setRuntimeProfileId(profile.getId());
    pool.setActiveRevisionId(revision.getId());
    pool.setRuntimeSpecJson(revision.getProfileJson());
    pool.setImageReference(withoutDigest(revision.getImageReference()));
    pool.setImageDigest(revision.getImageDigest());
    pool.setNetworkMode(networkMode);
    pool.setEgressAllowlistJson(egressPolicyCodec.normalize(
        networkMode, httpHosts(spec.network())
    ));
    pool.setSecretReferencesJson(secretService.normalizeReferences(
        spec.secrets().stream()
            .map(RuntimeMcpProfileSpec.SecretBinding::secretReference)
            .filter(reference -> !reference.startsWith("provider://"))
            .toList(),
        pool.getScopeType(),
        pool.getTenantId()
    ));
    requestReplicaReplacement(pool, now);
    if (pool.getStatus() != RuntimePoolStatus.DISABLED) {
      pool.setStatus(RuntimePoolStatus.SCALING);
    }
    pool.setLastActivityAt(now);
    pool.setLastError(null);
    return decorate(poolRepository.save(pool));
  }

  private static void requireSupportedOci(final RuntimeMcpProfileSpec spec) {
    if (spec.artifact().type() != RuntimeMcpProfileSpec.ArtifactType.OCI
        || spec.transport().type()
            == RuntimeMcpProfileSpec.TransportType.SSE_LEGACY) {
      throw new IllegalArgumentException(
          "Runtime Pools support OCI stdio or Streamable HTTP Profiles"
      );
    }
  }

  private static String networkMode(
      final RuntimeMcpProfileSpec.NetworkMode mode
  ) {
    return switch (mode) {
      case NONE -> "none";
      case HTTP_EGRESS -> "egress";
      case TCP_EGRESS -> "tcp-egress";
      case INTERNAL_SERVICE -> "internal-service";
    };
  }

  private static List<String> httpHosts(
      final RuntimeMcpProfileSpec.NetworkPolicy policy
  ) {
    if (policy.mode() != RuntimeMcpProfileSpec.NetworkMode.HTTP_EGRESS) {
      return policy.allowlist();
    }
    return policy.allowlist().stream().map(value -> {
      String normalized = value == null ? "" : value.trim();
      if (normalized.endsWith(":443")) {
        return normalized.substring(0, normalized.length() - 4);
      }
      if (normalized.endsWith(":80")) {
        return normalized.substring(0, normalized.length() - 3);
      }
      if (normalized.contains(":")) {
        throw new IllegalArgumentException(
            "HTTP egress supports only port 80 or 443"
        );
      }
      return normalized;
    }).toList();
  }

  private void requestReplicaReplacement(
      final AiRuntimePool pool,
      final Instant now
  ) {
    for (AiRuntimeWorkload workload
        : workloadRepository.findActiveByPool(pool.getId())) {
      if (terminal(workload.getStatus())) {
        continue;
      }
      if (workload.getStatus() == RuntimeWorkloadStatus.PENDING
          && workload.getLeaseId() == null) {
        workload.setStatus(RuntimeWorkloadStatus.CANCELLED);
        workload.setFinishedAt(now);
      } else {
        workload.setStatus(RuntimeWorkloadStatus.STOPPING);
        workload.setLastObservedAt(null);
      }
      workload.setLastError("Runtime Profile revision changed");
      workloadRepository.save(workload);
    }
  }

  private void assertSameOwner(
      final AiRuntimePool pool,
      final AiRuntimeMcpProfile profile
  ) {
    if (pool.getScopeType() != profile.getScopeType()
        || !Objects.equals(pool.getTenantId(), profile.getTenantId())) {
      throw new IllegalArgumentException(
          "Runtime Pool and Profile must have the same owner scope"
      );
    }
  }

  private AiRuntimePool decorate(final AiRuntimePool pool) {
    pool.setEgressAllowlist(egressPolicyCodec.decode(
        pool.getNetworkMode(), pool.getEgressAllowlistJson()
    ));
    pool.setSecretIds(secretService.references(
        pool.getSecretReferencesJson()
    ));
    return pool;
  }

  private static boolean terminal(final RuntimeWorkloadStatus status) {
    return status == RuntimeWorkloadStatus.SUCCEEDED
        || status == RuntimeWorkloadStatus.FAILED
        || status == RuntimeWorkloadStatus.LOST
        || status == RuntimeWorkloadStatus.CANCELLED;
  }

  private static String withoutDigest(final String imageReference) {
    int separator = imageReference.indexOf('@');
    return separator < 0
        ? imageReference : imageReference.substring(0, separator);
  }

  private static String identifier(final String value, final String label) {
    String normalized = value == null ? "" : value.trim();
    if (!IDENTIFIER.matcher(normalized).matches()) {
      throw new IllegalArgumentException(label + " is invalid");
    }
    return normalized;
  }
}
