package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;

/**
 * Frozen server-resolved inputs, without credentials or authorization snapshots. The existing
 * fingerprint service supplies the identity; the storage codec must not recompute it with a
 * different mapper and thereby change the identity of a previously accepted publication.
 */
record RecordPublicationIntent(
    int contractVersion,
    RecordWriteFenceKey key,
    RecordWritePolicyPublicationCommand command,
    String fingerprint,
    String previousFingerprint,
    String actorId,
    String evaluatedAt,
    Map<String, Binding> bindings,
    InstalledCalculation previousCalculation,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Map<String, Resource>> resources
) {
  RecordPublicationIntent {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(command, "command");
    bindings = Map.copyOf(bindings);
    Instant time = Instant.parse(evaluatedAt);
    if ((contractVersion != 1 && contractVersion != 2) || !key.modelId().equals(command.physicalModelId())
        || !checksum(fingerprint) || !checksum(previousFingerprint) || actorId == null || actorId.isBlank() || actorId.length() > 64
        || !actorId.equals(actorId.trim()) || !time.equals(time.truncatedTo(ChronoUnit.MICROS))
        || !time.toString().equals(evaluatedAt) || bindings.isEmpty() || bindings.size() > 6
        || previousCalculation != null && previousCalculation.revision() > command.expectedVersion()) {
      throw new IllegalArgumentException("Invalid frozen publication intent");
    }
    if (contractVersion == 1 && resources != null || contractVersion == 2 && resources == null) {
      throw new IllegalArgumentException("Publication resources must match the intent contract version");
    }
    if (resources != null) {
      if (!resources.keySet().equals(bindings.keySet()) || bindings.values().stream().anyMatch(binding -> binding.resourceGeneration() != 0)) {
        throw new IllegalArgumentException("Version two requires explicit resource snapshots and no legacy scalar generation");
      }
      var snapshots = new java.util.LinkedHashMap<String, Map<String, Resource>>();
      resources.forEach((model, items) -> {
        if (items.size() > 1024 || items.keySet().stream().anyMatch(code -> !identity(code, 160))
            || items.values().stream().map(Resource::bindingId).distinct().count() != items.size()
            || items.values().stream().anyMatch(resource -> resource.projection() != null
                && resource.projection().ownerKey() != null && !model.equals(resource.projection().physicalModelId()))) {
          throw new IllegalArgumentException("Invalid frozen publication resource association");
        }
        snapshots.put(model, Map.copyOf(items));
      });
      resources = Map.copyOf(snapshots);
    }
    var root = Objects.requireNonNull(bindings.get(key.modelId()), "root binding");
    if (!root.requestedSchema().equals(command.schemaReference())) {
      throw new IllegalArgumentException("Publication schema differs from the frozen root binding");
    }
    var expected = new HashSet<String>();
    expected.add(key.modelId());
    if (command.calculation() != null) {
      for (var scope : command.calculation().details().values()) {
        expected.add(scope.physicalModelId());
        var binding = bindings.get(scope.physicalModelId());
        if (binding == null || !binding.requestedSchema().equals(scope.schemaReference())) {
          throw new IllegalArgumentException("Publication detail binding is missing or inconsistent");
        }
      }
    }
    if (!expected.equals(bindings.keySet()) || bindings.values().stream().map(Binding::target).distinct().count() != bindings.size()) {
      throw new IllegalArgumentException("Publication bindings must match the owned models exactly");
    }
    for (var binding : bindings.values()) {
      if (!root.target().dataSourceId().equals(binding.target().dataSourceId())
          || !root.target().catalogName().equals(binding.target().catalogName())
          || !root.target().schemaName().equals(binding.target().schemaName())) {
        throw new IllegalArgumentException("Publication models must share one physical transaction target");
      }
    }
  }

  /** Retains the exact version-one JSON shape and checksums for existing durable journals. */
  RecordPublicationIntent(final int contractVersion, final RecordWriteFenceKey key, final RecordWritePolicyPublicationCommand command,
      final String fingerprint, final String previousFingerprint, final String actorId, final String evaluatedAt,
      final Map<String, Binding> bindings, final InstalledCalculation previousCalculation) {
    this(contractVersion, key, command, fingerprint, previousFingerprint, actorId, evaluatedAt, bindings, previousCalculation, null);
  }

  /** Binding identity is server-owned; a missing projection represents a legacy unmanaged association. */
  record Resource(String bindingId, Projection projection) {
    Resource {
      if (!identity(bindingId, 36)) {
        throw new IllegalArgumentException("Invalid publication resource binding identity");
      }
    }
  }

  /** Complete resource ownership fence, not a scalar maximum across unrelated pages. */
  record Projection(String id, String ownerKey, long generation, String physicalModelId) {
    Projection {
      if (!identity(id, 36) || generation < 0
          || ownerKey == null && (generation != 0 || physicalModelId != null)
          || ownerKey != null && (!identity(ownerKey, 160) || generation < 1 || !identity(physicalModelId, 36))) {
        throw new IllegalArgumentException("Invalid publication resource projection");
      }
    }
  }

  private static boolean identity(final String value, final int maximum) {
    return value != null && !value.isBlank() && value.length() <= maximum && value.equals(value.trim());
  }

  Instant clock() {
    return Instant.parse(evaluatedAt);
  }

  static boolean checksum(final String value) {
    return value != null && value.matches("[a-f0-9]{64}");
  }

  /** Versions and placement are checked again before every batch; mutable drafts are never consulted. */
  record Binding(RecordSchemaReference activeSchema, RecordSchemaReference requestedSchema,
      RuntimeMetadataTarget target, long resourceGeneration) {
    Binding {
      Objects.requireNonNull(activeSchema, "activeSchema");
      Objects.requireNonNull(requestedSchema, "requestedSchema");
      Objects.requireNonNull(target, "target");
      if (resourceGeneration < 0 || requestedSchema.version() > activeSchema.version()
          || requestedSchema.version() == activeSchema.version() && !requestedSchema.equals(activeSchema)) {
        throw new IllegalArgumentException("Invalid frozen publication binding");
      }
    }
  }

  /** The original installed definition remains immutable; cancellation must preserve this identity. */
  record InstalledCalculation(long revision, String checksum) {
    InstalledCalculation {
      if (revision < 1 || !RecordPublicationIntent.checksum(checksum)) {
        throw new IllegalArgumentException("Invalid original calculation identity");
      }
    }
  }
}
