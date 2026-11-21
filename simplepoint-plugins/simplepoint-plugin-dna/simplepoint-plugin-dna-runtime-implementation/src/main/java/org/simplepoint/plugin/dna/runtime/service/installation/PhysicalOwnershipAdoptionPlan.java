package org.simplepoint.plugin.dna.runtime.service.installation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.simplepoint.data.ownership.PhysicalAdoptionManifest;
import org.simplepoint.data.ownership.PhysicalNamespaceDescriptor;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeInfrastructureTable;

/** Complete immutable intent spanning native claims and their domain-owned control bindings. */
public record PhysicalOwnershipAdoptionPlan(
    UUID operation,
    String reviewFingerprint,
    PhysicalOwnershipModelReview models,
    PhysicalBindingTargetReview targets,
    List<PhysicalAdoptionManifest> manifests) {
  /**
   * Requires every legacy binding and every shared infrastructure owner in each adopted namespace.
   */
  public PhysicalOwnershipAdoptionPlan {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(models, "models");
    Objects.requireNonNull(targets, "targets");
    if (reviewFingerprint == null
        || !reviewFingerprint.matches("[0-9a-f]{64}")
        || manifests == null
        || manifests.size() > PhysicalBindingInventory.MAX_BINDINGS
        || !targets.controlFingerprint().equals(models.inventory().fingerprint())) {
      throw invalid();
    }
    var resolved =
        targets.bindings().stream()
            .collect(
                Collectors.toMap(PhysicalBindingTargetReview.Binding::bindingId, value -> value));
    var expected = new HashMap<PhysicalNamespaceDescriptor, Map<String, PhysicalObjectOwner>>();
    var identities = new HashMap<PhysicalNamespaceDescriptor, UUID>();
    var required = new HashSet<PhysicalNamespaceDescriptor>();
    if (resolved.size() != models.inventory().bindings().size()) {
      throw invalid();
    }
    for (var binding : models.inventory().bindings()) {
      var nativeBinding = resolved.get(binding.bindingId());
      if (nativeBinding == null
          || !nativeBinding.namespace().complete()
          || nativeBinding.namespace().namespaceId() == null) {
        throw invalid();
      }
      var namespace = nativeBinding.namespace().target();
      var id = nativeBinding.namespace().namespaceId();
      identities.put(namespace, id);
      if (binding.namespaceId() == null) {
        if (binding.revision() == Long.MAX_VALUE) {
          throw invalid();
        }
        required.add(namespace);
      } else if (!binding.namespaceId().equals(id.toString())) {
        throw invalid();
      }
      var owners = expected.computeIfAbsent(namespace, ignored -> new HashMap<>());
      if (owners.putIfAbsent(
              nativeBinding.table(),
              new PhysicalObjectOwner("dna-runtime", binding.tenantId(), binding.modelId()))
          != null) {
        throw invalid();
      }
    }
    for (var owners : expected.values()) {
      for (var table : RuntimeInfrastructureTable.values()) {
        if (owners.putIfAbsent(table.table(), table.owner()) != null) {
          throw invalid();
        }
      }
    }
    var covered = new HashSet<PhysicalNamespaceDescriptor>();
    for (var manifest : manifests) {
      if (!manifest.operation().equals(operation)
          || !manifest.reviewFingerprint().equals(reviewFingerprint)
          || !manifest.namespaceId().equals(identities.get(manifest.target()))
          || !covered.add(manifest.target())) {
        throw invalid();
      }
      var owners =
          manifest.claims().stream()
              .collect(
                  Collectors.toMap(
                      PhysicalAdoptionManifest.Claim::table,
                      PhysicalAdoptionManifest.Claim::owner));
      if (!owners.equals(expected.get(manifest.target()))) {
        throw invalid();
      }
    }
    if (!covered.containsAll(required)) {
      throw invalid();
    }
    manifests =
        manifests.stream().sorted(Comparator.comparing(m -> m.namespaceId().toString())).toList();
  }

  /** Stable intent identity; each component hash already uses its own canonical framing. */
  public String fingerprint() {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(
          ("dna-physical-adoption-v1\n"
                  + operation
                  + "\n"
                  + reviewFingerprint
                  + "\n"
                  + models.fingerprint()
                  + "\n"
                  + targets.fingerprint()
                  + "\n")
              .getBytes(StandardCharsets.UTF_8));
      for (var manifest : manifests) {
        digest.update((manifest.fingerprint() + "\n").getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required", impossible);
    }
  }

  /** Exact post-commit inventory, preserving all model and binding lifecycle state. */
  public PhysicalBindingInventory adoptedInventory() {
    var ids =
        targets.bindings().stream()
            .collect(
                Collectors.toMap(
                    PhysicalBindingTargetReview.Binding::bindingId,
                    value -> value.namespace().namespaceId().toString()));
    return new PhysicalBindingInventory(
        1,
        models.inventory().bindings().stream()
            .map(
                binding -> {
                  if (binding.namespaceId() != null) {
                    return binding;
                  }
                  return new PhysicalBindingInventory.Binding(
                      binding.bindingId(),
                      binding.modelId(),
                      binding.tenantId(),
                      binding.target(),
                      ids.get(binding.bindingId()),
                      binding.revision() + 1,
                      binding.deletedAt(),
                      binding.model(),
                      binding.cleanupEvidence());
                })
            .toList());
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("DNA_OWNERSHIP_ADOPTION_PLAN_INVALID");
  }
}
