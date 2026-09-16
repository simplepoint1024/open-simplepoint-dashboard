package org.simplepoint.plugin.ai.runtime.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageObservation;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageResolution;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeImageResolver;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeNodeOperations;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Resolves OCI tags through a healthy Tool Runtime Node and Docker Engine. */
@Service
public class AiRuntimeImageResolverImpl implements AiRuntimeImageResolver {

  private static final Pattern DIGEST =
      Pattern.compile("^sha256:[a-f0-9]{64}$");

  private final AiRuntimeNodeRepository nodeRepository;

  private final AiRuntimeNodeOperations nodeOperations;

  /** Creates the Runtime-backed image resolver. */
  public AiRuntimeImageResolverImpl(
      final AiRuntimeNodeRepository nodeRepository,
      final AiRuntimeNodeOperations nodeOperations
  ) {
    this.nodeRepository = nodeRepository;
    this.nodeOperations = nodeOperations;
  }

  @Override
  public RuntimeImageResolution resolve(final String imageReference) {
    AiRuntimeNode node = healthyNodes().stream().findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "No healthy Tool Runtime Node is available to resolve the OCI tag"
        ));
    RuntimeImageObservation observation = nodeOperations.prepare(
        node.getAdvertiseUrl(), imageReference
    );
    String digest = observedDigest(observation);
    if (digest == null) {
      throw new IllegalStateException(
          "Tool Runtime Node did not return an immutable OCI digest"
      );
    }
    return new RuntimeImageResolution(
        digest,
        observation.admissionPolicyHash()
    );
  }

  private List<AiRuntimeNode> healthyNodes() {
    Instant now = Instant.now();
    return nodeRepository.findAllActive(PageRequest.of(0, 100)).stream()
        .filter(node -> node.getStatus() == RuntimeNodeStatus.READY)
        .filter(node -> node.getHeartbeatExpiresAt() != null
            && node.getHeartbeatExpiresAt().isAfter(now))
        .sorted((left, right) -> left.getNodeId().compareTo(right.getNodeId()))
        .toList();
  }

  private static String observedDigest(
      final RuntimeImageObservation observation
  ) {
    if (observation.repoDigests() != null) {
      for (String repoDigest : observation.repoDigests()) {
        if (repoDigest == null) {
          continue;
        }
        int separator = repoDigest.lastIndexOf('@');
        String digest = separator < 0
            ? repoDigest : repoDigest.substring(separator + 1);
        if (DIGEST.matcher(digest).matches()) {
          return digest;
        }
      }
    }
    return observation.imageId() != null
        && DIGEST.matcher(observation.imageId()).matches()
        ? observation.imageId() : null;
  }
}
